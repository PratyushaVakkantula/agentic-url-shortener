package com.agentic.shortener.api;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.Instant;

/**
 * Shape checks only (presence, size). Business rules (URL safety, alias policy, expiry window)
 * live in the service so every caller gets them, not just HTTP.
 */
public record CreateLinkRequest(
        @Schema(description = "Absolute http(s) URL to shorten", example = "https://example.com/some/long/path?utm_source=x")
        @NotBlank @Size(max = 2048) String url,

        @Schema(description = "Optional custom alias, 3-30 chars of [A-Za-z0-9_-]", example = "launch-2026", nullable = true)
        @Size(min = 3, max = 30) String customAlias,

        @Schema(description = "Optional expiry (ISO-8601, UTC). Must be in the future and within 365 days.",
                example = "2026-12-31T23:59:59Z", nullable = true)
        Instant expiresAt) {
}
