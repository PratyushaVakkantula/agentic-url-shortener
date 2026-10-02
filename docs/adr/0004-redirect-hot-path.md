# ADR-0004: Redirect hot path: read-through cache and asynchronous click recording

**Status:** Accepted

## Context

Redirects are the dominant traffic, often orders of magnitude more than creations, and they
sit in front of a human waiting for a page. A naive implementation does, per redirect:
`SELECT link` + `INSERT click_event` + `UPDATE short_link SET click_count = click_count + 1`.
That is two writes on the latency-critical path, plus row-lock contention on the counter for a
viral link.

## Decision

1. **Read-through cache (Caffeine)** of an immutable `LinkSnapshot`, bounded by size, with a
   write TTL (default 10 min).
   - Status is **derived at request time** from the snapshot's timestamps, so caching can never
     make an expired link redirect.
   - Only positive lookups are cached. Unknown codes always hit the database, so a just-created
     alias is never hidden by a cached "not found".
   - Deactivation evicts the entry **after the transaction commits**. Evicting earlier would let
     a concurrent redirect reload the uncommitted (still active) row and put it back.
2. **Asynchronous click recording.** The request thread does a non-blocking `offer` into a
   bounded queue (default 10,000). One worker drains it in batches (≤ 500 or every 200 ms) and
   writes each batch in one transaction, with **one counter `UPDATE` per distinct link per
   batch**, done in id order to avoid deadlocks.
3. **Explicit loss semantics.** Queue full → click dropped and counted
   (`shortener.clicks.dropped`). Batch write failure → batch dropped and counted
   (`shortener.clicks.failed`). On shutdown the recorder stops *after* the web server and
   drains the queue before the connection pool closes.
4. **302 + `Cache-Control: no-store`**, so browsers do not bypass us (see A-2).

## Consequences

- **+** The redirect path does no database write and usually no database read.
- **+** Hot links cost one counter update per batch instead of one per click.
- **+** Every way analytics can lose data is bounded, deliberate and visible in metrics.
- **−** Analytics are eventually consistent: up to one flush interval behind.
- **−** Clicks still in the queue are lost if the process is killed (`kill -9`, OOM). For
  billing-grade counting, replace the in-memory queue with a durable log (Kafka) and make
  the consumer idempotent.
- **−** Across multiple instances, a deactivated link can still redirect on *other*
  instances for up to one cache TTL. The fix when scaling out is cache invalidation over
  pub/sub (e.g. Redis), or a shorter TTL.
- **−** `IDENTITY` primary keys prevent Hibernate from batching the event `INSERT`s. At higher
  volume, switch `click_event` to a sequence with an allocation size.

## Bugs found while implementing (kept as regression tests)

- **Time-zone bucketing.** Found in the live demo, in three layers:
  1. Grouping with a DB-side `extract(date …)` used the session time zone. On a server in
     America/New_York, a 00:42Z click counted toward the previous day. Fix: V2 stores the
     UTC day, computed in Java at write time.
  2. V2's backfill (`CAST(… AT TIME ZONE 'UTC' AS DATE)`) had the same flaw. A JDBC probe
     showed H2 converts in the session zone regardless of `AT TIME ZONE`. Fix: V3 sets the
     session to UTC and recomputes. V2 is left untouched because applied migrations are
     immutable. `MigrationTest` upgrades V2 data to V3 and checks the result.
  3. With sessions in UTC, Hibernate read `DATE`s through legacy `java.sql.Date`, which
     shifts a day when JVM zone ≠ session zone (proved by the same probe). Fix:
     `hibernate.type.java_time_use_direct_jdbc=true`.

  The test suite now runs with `-Duser.timezone=America/New_York`, so any local-time
  assumption fails in CI, where runners default to UTC and would hide it.
- **Lock starvation.** The worker's loop re-acquired a non-fair `ReentrantLock` ahead of a
  waiting `flush()`, measured at up to 66 s per flush. Fix: a fair lock (worst case 165 ms).
