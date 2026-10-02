package com.agentic.shortener.repository;

import com.agentic.shortener.domain.ClickEvent;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Analytics queries. All aggregate in the database (served by ix_click_event_link_time),
 * never by loading events into memory.
 */
public interface ClickEventRepository extends JpaRepository<ClickEvent, Long> {

    /** Groups on the stored UTC day column (V2), never on a DB-side date conversion. */
    @Query("""
            select new com.agentic.shortener.repository.DailyClicks(e.occurredDay, count(e))
            from ClickEvent e
            where e.linkId = :linkId and e.occurredDay >= :fromDay
            group by e.occurredDay
            order by e.occurredDay""")
    List<DailyClicks> countPerDay(@Param("linkId") long linkId, @Param("fromDay") LocalDate fromDay);

    @Query("""
            select new com.agentic.shortener.repository.LabelCount(e.referrerHost, count(e))
            from ClickEvent e
            where e.linkId = :linkId and e.occurredAt >= :from and e.referrerHost is not null
            group by e.referrerHost
            order by count(e) desc, e.referrerHost""")
    List<LabelCount> topReferrers(@Param("linkId") long linkId, @Param("from") Instant from, Limit limit);

    @Query("""
            select new com.agentic.shortener.repository.LabelCount(e.userAgentFamily, count(e))
            from ClickEvent e
            where e.linkId = :linkId and e.occurredAt >= :from
            group by e.userAgentFamily
            order by count(e) desc, e.userAgentFamily""")
    List<LabelCount> countPerBrowser(@Param("linkId") long linkId, @Param("from") Instant from);
}
