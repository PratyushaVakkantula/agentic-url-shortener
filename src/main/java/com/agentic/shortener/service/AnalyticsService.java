package com.agentic.shortener.service;

import com.agentic.shortener.domain.LinkNotFoundException;
import com.agentic.shortener.domain.ShortLink;
import com.agentic.shortener.repository.ClickEventRepository;
import com.agentic.shortener.repository.DailyClicks;
import com.agentic.shortener.repository.LabelCount;
import com.agentic.shortener.repository.ShortLinkRepository;
import com.agentic.shortener.service.LinkAnalytics.DayCount;
import com.agentic.shortener.service.LinkAnalytics.NamedCount;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Click analytics (FR-5). Eventually consistent by design: clicks become visible after the
 * recorder's next flush (≤ flush-interval, 200 ms by default).
 */
@Service
public class AnalyticsService {

    public static final int MAX_WINDOW_DAYS = 365;
    private static final int TOP_REFERRERS = 10;

    private final ShortLinkRepository links;
    private final ClickEventRepository clicks;
    private final Clock clock;

    public AnalyticsService(ShortLinkRepository links, ClickEventRepository clicks, Clock clock) {
        this.links = links;
        this.clicks = clicks;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public LinkAnalytics analytics(String code, int days) {
        if (days < 1 || days > MAX_WINDOW_DAYS) {
            throw new IllegalArgumentException("days must be between 1 and " + MAX_WINDOW_DAYS);
        }
        ShortLink link = links.findByCode(code).orElseThrow(() -> new LinkNotFoundException(code));

        LocalDate today = LocalDate.ofInstant(clock.instant(), ZoneOffset.UTC);
        LocalDate start = today.minusDays(days - 1L);
        Instant from = start.atStartOfDay(ZoneOffset.UTC).toInstant();

        return new LinkAnalytics(
                link.getCode(),
                link.getClickCount(),
                link.getLastAccessedAt(),
                start,
                today,
                zeroFill(clicks.countPerDay(link.getId(), start), start, today),
                named(clicks.topReferrers(link.getId(), from, Limit.of(TOP_REFERRERS))),
                named(clicks.countPerBrowser(link.getId(), from)));
    }

    private static List<DayCount> zeroFill(List<DailyClicks> sparse, LocalDate start, LocalDate end) {
        Map<LocalDate, Long> byDay = sparse.stream().collect(Collectors.toMap(DailyClicks::day, DailyClicks::clicks));
        List<DayCount> filled = new ArrayList<>();
        for (LocalDate d = start; !d.isAfter(end); d = d.plusDays(1)) {
            filled.add(new DayCount(d, byDay.getOrDefault(d, 0L)));
        }
        return filled;
    }

    private static List<NamedCount> named(List<LabelCount> counts) {
        return counts.stream().map(c -> new NamedCount(c.label(), c.count())).toList();
    }
}
