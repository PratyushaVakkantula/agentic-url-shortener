# ADR-0006: Durable event store and crash recovery

**Status:** Accepted

## Context

ADR-0005 made run state a fold over events. Those events must now survive restarts (OR-15), be
queryable as an audit trail (OR-10), and allow interrupted runs to continue, including runs
paused for human approval for hours or days.

## Decision

1. **Storage.** Two tables (Flyway V4):
   - `workflow_event`: append-only, primary key `(run_id, seq)`, JSON payload (`TEXT`, portable
     across H2 and PostgreSQL), `event_type`, `schema_version`.
   - `workflow_run`: a read model (status, initiator, title, timestamps, `last_seq`) updated **in
     the same transaction** as every append.
2. **Single writer per run.** An append for `seq = N` first runs
   `UPDATE workflow_run SET last_seq = N WHERE run_id = ? AND last_seq = N-1`. Zero rows updated
   means another writer got there first, and the append is rejected
   (`ConcurrentRunModificationException`). The primary key is an independent second guard.
3. **Plain JDBC (`JdbcClient`)** rather than JPA. The data is a log, not an object graph, so an ORM
   would add mapping and caching semantics with no benefit.
4. **Codec.** The event type registry is derived from the sealed `RunEvent` hierarchy, so it cannot
   drift from the code. `schema_version` reserves the place for upcasters when a payload shape
   changes; stored history is never rewritten.
5. **Shutdown behaves like a crash.** Stopping the engine interrupts coordinators **without writing
   anything**. Unfinished runs stay `RUNNING`.
6. **Recovery on startup.** For each `RUNNING` run without a live coordinator: replay, append
   `RunResumed(interruptedStages)` (which moves those stages back to `PENDING`), then schedule as
   normal. Interrupted stages run again as a new attempt. This is safe because agents only produce
   proposals and have no external side effects.
7. **Definition drift is fatal by design.** If the run's workflow `name` + `version` is no longer
   deployed, the run is failed with an explicit reason instead of continuing under a different
   process definition.

## Consequences

- **+** Shutdown and crash take the same code path, and that path is tested
  (`runInterruptedMidStageIsResumedByTheNextProcess`): the completed stage is not redone, and the
  interrupted stage runs again as attempt 2.
- **+** Audit trail, state and run list come from one transactional write, so they never disagree.
- **+** Finished runs stay fully queryable after restarts (rebuilt by replay).
- **−** Recovery assumes **one engine instance per database**. With several, the sequence check
  stops a second resumer from corrupting a log, but the losing instance's coordinator fails. A
  lease column (`owner`, `lease_until`) acquired before resuming is the scale-out fix.
- **−** Replaying long runs on every read of a finished run costs O(events). Fine at this scale; a
  snapshot every N events, or caching finished views, is the standard remedy.
- **−** If agents ever gain side effects (opening PRs, calling deploy APIs), re-execution after a
  crash must become idempotent (idempotency keys derived from `runId/stage/attempt`).
