package com.agentic.shortener.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.Objects;

/**
 * A short link. State changes go through intention-revealing methods; there are no setters,
 * so the entity cannot be put into an invalid state from outside.
 *
 * <p>Status is derived from timestamps rather than stored, so a link expires by the passage
 * of time alone: no scheduled job is needed and status can never be stale.
 */
@Entity
@Table(name = "short_link")
public class ShortLink {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, updatable = false, length = 30)
    private String code;

    @Column(name = "original_url", nullable = false, updatable = false, length = 2048)
    private String originalUrl;

    @Column(name = "custom_alias", nullable = false, updatable = false)
    private boolean customAlias;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "expires_at", updatable = false)
    private Instant expiresAt;

    @Column(name = "deactivated_at")
    private Instant deactivatedAt;

    /** Maintained by an atomic UPDATE in the repository, never incremented in memory. */
    @Column(name = "click_count", nullable = false, insertable = false, updatable = false)
    private long clickCount;

    @Column(name = "last_accessed_at", insertable = false, updatable = false)
    private Instant lastAccessedAt;

    @Version
    private long version;

    protected ShortLink() {
        // for JPA
    }

    private ShortLink(String code, String originalUrl, boolean customAlias, Instant createdAt, Instant expiresAt) {
        this.code = Objects.requireNonNull(code, "code");
        this.originalUrl = Objects.requireNonNull(originalUrl, "originalUrl");
        this.customAlias = customAlias;
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt");
        this.expiresAt = expiresAt;
    }

    /** Callers are expected to have validated url, code and expiry (see ShortLinkService). */
    public static ShortLink create(String code, String originalUrl, boolean customAlias,
                                   Instant createdAt, Instant expiresAt) {
        if (expiresAt != null && !expiresAt.isAfter(createdAt)) {
            throw new IllegalArgumentException("expiresAt must be after createdAt");
        }
        return new ShortLink(code, originalUrl, customAlias, createdAt, expiresAt);
    }

    public LinkStatus statusAt(Instant now) {
        if (deactivatedAt != null) {
            return LinkStatus.DEACTIVATED;
        }
        if (expiresAt != null && !now.isBefore(expiresAt)) {
            return LinkStatus.EXPIRED;
        }
        return LinkStatus.ACTIVE;
    }

    /** Idempotent: deactivating an already deactivated link keeps the original timestamp. */
    public void deactivate(Instant now) {
        if (deactivatedAt == null) {
            deactivatedAt = now;
        }
    }

    public Long getId() {
        return id;
    }

    public String getCode() {
        return code;
    }

    public String getOriginalUrl() {
        return originalUrl;
    }

    public boolean isCustomAlias() {
        return customAlias;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public Instant getDeactivatedAt() {
        return deactivatedAt;
    }

    public long getClickCount() {
        return clickCount;
    }

    public Instant getLastAccessedAt() {
        return lastAccessedAt;
    }
}
