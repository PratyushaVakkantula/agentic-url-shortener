package com.agentic.shortener.api;

import com.agentic.shortener.domain.LinkStatus;
import com.agentic.shortener.domain.ShortLink;
import java.time.Instant;

/** Public representation of a link. Decoupled from the entity so the schema can evolve freely. */
public record LinkResponse(
        String code,
        String shortUrl,
        String originalUrl,
        boolean customAlias,
        LinkStatus status,
        Instant createdAt,
        Instant expiresAt,
        Instant deactivatedAt) {

    static LinkResponse of(ShortLink link, String shortUrl, Instant now) {
        return new LinkResponse(link.getCode(), shortUrl, link.getOriginalUrl(), link.isCustomAlias(),
                link.statusAt(now), link.getCreatedAt(), link.getExpiresAt(), link.getDeactivatedAt());
    }
}
