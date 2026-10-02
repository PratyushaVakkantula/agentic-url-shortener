package com.agentic.shortener.service;

import com.agentic.shortener.domain.LinkNotFoundException;
import com.agentic.shortener.domain.LinkStatus;
import com.agentic.shortener.domain.LinkUnavailableException;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Instant;
import org.springframework.stereotype.Service;

/**
 * The hot path: code → target URL. One cache lookup, one status check, one non-blocking
 * enqueue. No database write happens on the request thread.
 */
@Service
public class RedirectService {

    private final RedirectCache cache;
    private final ClickRecorder clicks;
    private final MeterRegistry meters;
    private final Clock clock;

    public RedirectService(RedirectCache cache, ClickRecorder clicks, MeterRegistry meters, Clock clock) {
        this.cache = cache;
        this.clicks = clicks;
        this.meters = meters;
        this.clock = clock;
    }

    /**
     * @return the URL to redirect to
     * @throws LinkNotFoundException    unknown code (404)
     * @throws LinkUnavailableException expired or deactivated (410)
     */
    public String resolve(String code, String referer, String userAgent) {
        Instant now = clock.instant();
        LinkSnapshot link = cache.get(code).orElseThrow(() -> {
            count("not_found");
            return new LinkNotFoundException(code);
        });

        LinkStatus status = link.statusAt(now);
        if (status != LinkStatus.ACTIVE) {
            count("gone");
            throw new LinkUnavailableException(code, status);
        }

        clicks.record(new ClickCommand(link.id(), now,
                ClickContext.referrerHost(referer), ClickContext.userAgentFamily(userAgent)));
        count("redirected");
        return link.originalUrl();
    }

    private void count(String outcome) {
        meters.counter("shortener.redirects", "outcome", outcome).increment();
    }
}
