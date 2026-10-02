package com.agentic.platform.web.ratelimit;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * Declarative rate-limit rules ({@code app.rate-limit.*}). Rules are configuration, not code,
 * so the platform filter stays independent of the business modules it protects.
 */
@Validated
@ConfigurationProperties("app.rate-limit")
public record RateLimitProperties(
        @DefaultValue("true") boolean enabled,
        @DefaultValue List<@Valid Rule> rules) {

    /**
     * @param name         bucket namespace, also exposed in metrics/logs
     * @param method       HTTP method the rule applies to
     * @param path         Spring path pattern, e.g. {@code /api/v1/urls}
     * @param capacity     burst size = requests allowed per refill period
     * @param refillPeriod time to refill a fully drained bucket
     */
    public record Rule(@NotBlank String name, @NotBlank String method, @NotBlank String path,
                       @Min(1) long capacity, @NotNull Duration refillPeriod) {
    }
}
