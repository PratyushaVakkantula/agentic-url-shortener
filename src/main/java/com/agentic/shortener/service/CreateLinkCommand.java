package com.agentic.shortener.service;

import java.time.Instant;

/**
 * Input to {@link ShortLinkService#create}. Decoupled from the HTTP DTO so the service can be
 * driven by other callers (batch import, tests) without depending on the web layer.
 *
 * @param customAlias optional; null means "generate a code"
 * @param expiresAt   optional; null means "never expires"
 */
public record CreateLinkCommand(String url, String customAlias, Instant expiresAt) {
}
