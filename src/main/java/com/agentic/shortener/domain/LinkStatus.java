package com.agentic.shortener.domain;

public enum LinkStatus {
    ACTIVE,
    /** Past its {@code expiresAt}; redirects answer 410 Gone. */
    EXPIRED,
    /** Explicitly deactivated by an admin; kept for audit, redirects answer 410 Gone. */
    DEACTIVATED
}
