-- Fix: per-day analytics were bucketed by the database session's time zone, so on a server
-- running in America/New_York a click at 00:30Z counted toward the previous day.
-- The UTC day is now computed by the application at write time and stored explicitly, which
-- makes bucketing independent of server, JVM and database time-zone settings.
--
-- Expand/backfill/contract in one migration (safe while the table is small; for a large table
-- this would be split across releases: add nullable → backfill in batches → add NOT NULL).

ALTER TABLE click_event ADD COLUMN occurred_day DATE;

UPDATE click_event SET occurred_day = CAST(occurred_at AT TIME ZONE 'UTC' AS DATE);

ALTER TABLE click_event ALTER COLUMN occurred_day SET NOT NULL;

CREATE INDEX ix_click_event_link_day ON click_event (link_id, occurred_day);
