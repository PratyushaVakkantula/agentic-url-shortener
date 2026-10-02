package com.agentic.shortener.service;

import com.agentic.shortener.domain.ClickEvent;
import com.agentic.shortener.repository.ClickEventRepository;
import com.agentic.shortener.repository.ShortLinkRepository;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Persists a batch of clicks in one transaction: one insert per event plus <b>one counter
 * update per distinct link</b>. A viral link clicked 500 times in a batch costs a single
 * UPDATE, not 500 contended row updates.
 */
@Component
public class ClickBatchWriter {

    private final ClickEventRepository events;
    private final ShortLinkRepository links;

    public ClickBatchWriter(ClickEventRepository events, ShortLinkRepository links) {
        this.events = events;
        this.links = links;
    }

    @Transactional
    public void write(List<ClickCommand> batch) {
        events.saveAll(batch.stream()
                .map(c -> new ClickEvent(c.linkId(), c.occurredAt(), c.referrerHost(), c.userAgentFamily()))
                .toList());

        // TreeMap: update rows in a stable id order so concurrent writers cannot deadlock.
        Map<Long, Aggregate> perLink = new TreeMap<>();
        for (ClickCommand c : batch) {
            perLink.merge(c.linkId(), new Aggregate(1, c.occurredAt()), Aggregate::plus);
        }
        perLink.forEach((linkId, agg) -> links.incrementClicks(linkId, agg.count, agg.latest));
    }

    private record Aggregate(long count, Instant latest) {
        Aggregate plus(Aggregate other) {
            return new Aggregate(count + other.count, latest.isAfter(other.latest) ? latest : other.latest);
        }
    }
}
