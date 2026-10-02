package com.agentic.shortener.service;

import com.agentic.shortener.repository.ShortLinkRepository;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.cache.CaffeineCacheMetrics;
import java.util.Optional;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Read-through cache for the redirect path (NFR-9). Positive results only: unknown codes are
 * not cached, so a link created a moment ago is never hidden by a cached "not found".
 */
@Component
public class RedirectCache {

    private final Cache<String, LinkSnapshot> cache;
    private final ShortLinkRepository links;

    public RedirectCache(ShortenerRuntimeProperties properties, ShortLinkRepository links, MeterRegistry meters) {
        this.links = links;
        this.cache = Caffeine.newBuilder()
                .maximumSize(properties.redirectCache().maxSize())
                .expireAfterWrite(properties.redirectCache().ttl())
                .recordStats()
                .build();
        CaffeineCacheMetrics.monitor(meters, cache, "shortener.redirect");
    }

    Optional<LinkSnapshot> get(String code) {
        // Caffeine does not store null, which gives "no negative caching" for free.
        return Optional.ofNullable(cache.get(code, c -> links.findByCode(c).map(LinkSnapshot::of).orElse(null)));
    }

    /**
     * Evicts after the surrounding transaction commits. Evicting earlier would let a concurrent
     * redirect reload the old row (not yet committed) and put the stale value straight back.
     */
    void evictAfterCommit(String code) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    cache.invalidate(code);
                }
            });
        } else {
            cache.invalidate(code);
        }
    }
}
