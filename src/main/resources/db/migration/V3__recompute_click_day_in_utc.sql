-- Data fix for V2's backfill. V2 used CAST(occurred_at AT TIME ZONE 'UTC' AS DATE), but H2 (and
-- PostgreSQL) convert TIMESTAMP WITH TIME ZONE to DATE in the *session* time zone, so on a
-- server in America/New_York, rows backfilled by V2 could land on the previous day.
-- Verified empirically: only the session time zone changes the result.
--
-- V2 is not edited (applied migrations are immutable, checksums would fail everywhere it ran);
-- this migration recomputes every row. Rows written by the application since V2 are already
-- correct and are recomputed to the same value, so the fix is idempotent.

SET TIME ZONE 'UTC';

UPDATE click_event SET occurred_day = CAST(occurred_at AS DATE);
