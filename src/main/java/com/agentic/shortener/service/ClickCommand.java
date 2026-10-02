package com.agentic.shortener.service;

import java.time.Instant;

/** A click captured on the request thread, waiting to be persisted by {@link ClickRecorder}. */
public record ClickCommand(long linkId, Instant occurredAt, String referrerHost, String userAgentFamily) {
}
