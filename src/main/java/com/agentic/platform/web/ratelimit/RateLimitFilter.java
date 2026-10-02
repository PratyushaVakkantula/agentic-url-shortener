package com.agentic.platform.web.ratelimit;

import com.agentic.platform.web.ApiProblem;
import com.agentic.platform.web.ProblemDetailWriter;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.PathContainer;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.pattern.PathPattern;
import org.springframework.web.util.pattern.PathPatternParser;

/**
 * Per-client rate limiting (NFR-4). Rejected requests get {@code 429} with {@code Retry-After};
 * every limited request carries {@code RateLimit-Limit} / {@code RateLimit-Remaining}.
 *
 * <p>Runs right after request-id assignment and <i>before</i> Spring Security, so floods are
 * rejected before they cost a bcrypt password check.
 *
 * <p>Limitations (documented trade-offs):
 * <ul>
 *   <li>Buckets are per instance (in-memory). With N instances the effective limit is N×; a
 *       shared store (Redis) is the scale-out path.</li>
 *   <li>The client key is the socket address. Behind a reverse proxy, configure
 *       {@code server.forward-headers-strategy} so it reflects the real client, and only trust
 *       forwarded headers set by your own proxy.</li>
 *   <li>Bucket state is bounded (max entries + idle expiry), so a flood of distinct IPs
 *       cannot exhaust memory; an evicted client simply starts with a full bucket.</li>
 * </ul>
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class RateLimitFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(RateLimitFilter.class);
    private static final int MAX_TRACKED_CLIENTS = 100_000;

    private final boolean enabled;
    private final List<CompiledRule> rules;
    private final Cache<String, TokenBucket> buckets;
    private final ProblemDetailWriter problemWriter;
    private final MeterRegistry meters;
    private final Clock clock;

    public RateLimitFilter(RateLimitProperties properties, ProblemDetailWriter problemWriter,
                           MeterRegistry meters, Clock clock) {
        this.enabled = properties.enabled();
        this.rules = properties.rules().stream().map(CompiledRule::new).toList();
        Duration longestPeriod = properties.rules().stream().map(RateLimitProperties.Rule::refillPeriod)
                .max(Duration::compareTo).orElse(Duration.ofMinutes(1));
        this.buckets = Caffeine.newBuilder()
                .maximumSize(MAX_TRACKED_CLIENTS)
                // An idle bucket is full again after one refill period, so forgetting it is lossless.
                .expireAfterAccess(longestPeriod)
                .build();
        this.problemWriter = problemWriter;
        this.meters = meters;
        this.clock = clock;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !enabled || rules.isEmpty();
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        CompiledRule rule = match(request);
        if (rule == null) {
            chain.doFilter(request, response);
            return;
        }

        String key = rule.rule.name() + ':' + request.getRemoteAddr();
        long now = clock.millis();
        TokenBucket bucket = buckets.get(key, k -> new TokenBucket(rule.rule.capacity(), rule.rule.refillPeriod().toMillis(), now));
        TokenBucket.Result result = bucket.tryConsume(now);

        response.setHeader("RateLimit-Limit", String.valueOf(bucket.capacity()));
        response.setHeader("RateLimit-Remaining", String.valueOf(result.remaining()));

        if (result.allowed()) {
            chain.doFilter(request, response);
            return;
        }

        meters.counter("ratelimit.rejected", "rule", rule.rule.name()).increment();
        log.info("Rate limit exceeded for rule '{}'", rule.rule.name());
        response.setHeader(HttpHeaders.RETRY_AFTER, String.valueOf(result.retryAfterSeconds()));
        problemWriter.write(response, ApiProblem.of(HttpStatus.TOO_MANY_REQUESTS, "RATE_LIMITED",
                "Too many requests. Retry after " + result.retryAfterSeconds() + " second(s).", request));
    }

    private CompiledRule match(HttpServletRequest request) {
        PathContainer path = PathContainer.parsePath(request.getRequestURI());
        for (CompiledRule rule : rules) {
            if (rule.rule.method().equalsIgnoreCase(request.getMethod()) && rule.pattern.matches(path)) {
                return rule;
            }
        }
        return null;
    }

    private record CompiledRule(RateLimitProperties.Rule rule, PathPattern pattern) {
        CompiledRule(RateLimitProperties.Rule rule) {
            this(rule, PathPatternParser.defaultInstance.parse(rule.path()));
        }
    }
}
