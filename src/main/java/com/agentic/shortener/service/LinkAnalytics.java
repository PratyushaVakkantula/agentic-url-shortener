package com.agentic.shortener.service;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * Analytics for one link. Uses its own value types rather than the repository's query
 * projections, so persistence details never leak to callers (enforced by ArchitectureTest).
 *
 * @param totalClicks  lifetime total (from the denormalized counter, O(1))
 * @param windowStart  first UTC day included in the breakdowns
 * @param clicksPerDay one entry per day in the window, zero-filled so charts need no gap logic
 */
public record LinkAnalytics(
        String code,
        long totalClicks,
        Instant lastAccessedAt,
        LocalDate windowStart,
        LocalDate windowEnd,
        List<DayCount> clicksPerDay,
        List<NamedCount> topReferrers,
        List<NamedCount> browsers) {

    public record DayCount(LocalDate day, long clicks) {
    }

    public record NamedCount(String name, long clicks) {
    }
}
