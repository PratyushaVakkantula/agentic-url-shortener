package com.agentic.shortener.api;

import com.agentic.shortener.service.LinkAnalytics;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/** Analytics payload. Days are UTC calendar days; breakdowns cover [windowStart, windowEnd]. */
public record AnalyticsResponse(
        String code,
        long totalClicks,
        Instant lastAccessedAt,
        LocalDate windowStart,
        LocalDate windowEnd,
        List<Day> clicksPerDay,
        List<Count> topReferrers,
        List<Count> browsers) {

    public record Day(LocalDate date, long clicks) {
    }

    public record Count(String name, long clicks) {
    }

    static AnalyticsResponse of(LinkAnalytics a) {
        return new AnalyticsResponse(a.code(), a.totalClicks(), a.lastAccessedAt(), a.windowStart(), a.windowEnd(),
                a.clicksPerDay().stream().map(d -> new Day(d.day(), d.clicks())).toList(),
                a.topReferrers().stream().map(c -> new Count(c.name(), c.clicks())).toList(),
                a.browsers().stream().map(c -> new Count(c.name(), c.clicks())).toList());
    }
}
