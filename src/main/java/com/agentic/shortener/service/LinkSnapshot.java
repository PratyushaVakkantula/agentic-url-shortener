package com.agentic.shortener.service;

import com.agentic.shortener.domain.LinkStatus;
import com.agentic.shortener.domain.ShortLink;
import java.time.Instant;

/**
 * Immutable, cache-safe view of the fields a redirect needs. Caching this instead of the JPA
 * entity avoids sharing a managed object across threads and transactions.
 *
 * <p>Status is re-evaluated against the current time on every use, so a cached link still
 * stops redirecting the instant it expires; caching never extends a link's life.
 */
record LinkSnapshot(long id, String code, String originalUrl, Instant expiresAt, Instant deactivatedAt) {

    static LinkSnapshot of(ShortLink link) {
        return new LinkSnapshot(link.getId(), link.getCode(), link.getOriginalUrl(),
                link.getExpiresAt(), link.getDeactivatedAt());
    }

    LinkStatus statusAt(Instant now) {
        if (deactivatedAt != null) {
            return LinkStatus.DEACTIVATED;
        }
        if (expiresAt != null && !now.isBefore(expiresAt)) {
            return LinkStatus.EXPIRED;
        }
        return LinkStatus.ACTIVE;
    }
}
