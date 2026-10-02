package com.agentic.shortener.repository;

import java.time.LocalDate;

/** Clicks on one UTC calendar day. */
public record DailyClicks(LocalDate day, long clicks) {
}
