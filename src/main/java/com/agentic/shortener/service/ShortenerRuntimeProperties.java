package com.agentic.shortener.service;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/** Tuning for the redirect hot path ({@code app.shortener.redirect-cache / click-tracking}). */
@Validated
@ConfigurationProperties("app.shortener")
public record ShortenerRuntimeProperties(
        @Valid @DefaultValue CacheSettings redirectCache,
        @Valid @DefaultValue ClickTrackingSettings clickTracking) {

    /**
     * @param maxSize bounded so memory use is predictable under a scan of many codes
     * @param ttl     upper bound on staleness across instances (see ADR-0004)
     */
    public record CacheSettings(@Min(0) @DefaultValue("10000") long maxSize,
                                @NotNull @DefaultValue("10m") Duration ttl) {
    }

    /**
     * @param queueCapacity clicks buffered before new ones are dropped (never blocks redirects)
     * @param batchSize     max clicks written per transaction
     * @param flushInterval max delay before a buffered click is written
     */
    public record ClickTrackingSettings(@Min(1) @DefaultValue("10000") int queueCapacity,
                                @Min(1) @Max(5000) @DefaultValue("500") int batchSize,
                                @NotNull @DefaultValue("200ms") Duration flushInterval) {
    }
}
