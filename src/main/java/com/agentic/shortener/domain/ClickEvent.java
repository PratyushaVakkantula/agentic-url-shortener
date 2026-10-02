package com.agentic.shortener.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Objects;

/**
 * One successful redirect. Deliberately privacy-minimal (NFR-6): no IP address, only the
 * referrer's host (not its path/query, which can contain personal data) and a coarse
 * browser family instead of the full user-agent fingerprint.
 */
@Entity
@Table(name = "click_event")
public class ClickEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "link_id", nullable = false, updatable = false)
    private Long linkId;

    @Column(name = "occurred_at", nullable = false, updatable = false)
    private Instant occurredAt;

    @Column(name = "referrer_host", updatable = false)
    private String referrerHost;

    @Column(name = "user_agent_family", nullable = false, updatable = false, length = 32)
    private String userAgentFamily;

    protected ClickEvent() {
        // for JPA
    }

    public ClickEvent(Long linkId, Instant occurredAt, String referrerHost, String userAgentFamily) {
        this.linkId = Objects.requireNonNull(linkId, "linkId");
        this.occurredAt = Objects.requireNonNull(occurredAt, "occurredAt");
        this.referrerHost = referrerHost;
        this.userAgentFamily = Objects.requireNonNull(userAgentFamily, "userAgentFamily");
    }

    public Long getLinkId() {
        return linkId;
    }

    public Instant getOccurredAt() {
        return occurredAt;
    }

    public String getReferrerHost() {
        return referrerHost;
    }

    public String getUserAgentFamily() {
        return userAgentFamily;
    }
}
