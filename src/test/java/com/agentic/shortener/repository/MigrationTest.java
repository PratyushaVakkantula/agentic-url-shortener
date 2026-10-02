package com.agentic.shortener.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.TimeZone;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Tests migrations as upgrades, not just as a fresh schema: migrate to an older version, put
 * data in the shape that version produced, then upgrade and check the data.
 *
 * <p>Uses plain JDBC connections (no Spring, no connection-init-sql) so each migration must be
 * correct on its own, whatever the session time zone happens to be.
 */
class MigrationTest {

    @BeforeAll
    static void requireNonUtcZone() {
        // Surefire runs tests in America/New_York; the bug only shows in a non-UTC zone.
        assertThat(TimeZone.getDefault().getID()).isNotEqualTo("UTC");
    }

    @Test
    void v3RecomputesClickDaysThatV2BackfilledInTheSessionTimeZone() throws Exception {
        String url = "jdbc:h2:mem:migration-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1";
        Flyway.configure().dataSource(url, "sa", "").target("2").load().migrate();

        try (Connection c = DriverManager.getConnection(url, "sa", ""); Statement s = c.createStatement()) {
            s.execute("""
                    INSERT INTO short_link (code, original_url, custom_alias, created_at)
                    VALUES ('abc1234', 'https://example.com', FALSE, TIMESTAMP WITH TIME ZONE '2026-10-01 00:00:00+00')""");
            // 00:42Z on Oct 2, stored with the wrong day exactly as V2's backfill produced it.
            s.execute("""
                    INSERT INTO click_event (link_id, occurred_at, occurred_day, user_agent_family)
                    SELECT id, TIMESTAMP WITH TIME ZONE '2026-10-02 00:42:00+00', DATE '2026-10-01', 'Chrome'
                    FROM short_link""");
        }

        Flyway.configure().dataSource(url, "sa", "").load().migrate();

        try (Connection c = DriverManager.getConnection(url, "sa", "");
             Statement s = c.createStatement();
             ResultSet r = s.executeQuery("SELECT occurred_day FROM click_event")) {
            r.next();
            assertThat(r.getString(1)).isEqualTo("2026-10-02");
        }
    }
}
