# ADR-0008: Hash-based re-planning and log-derived reliability metrics

**Status:** Accepted

## Context

OR-12: *dynamically re-plan when upstream outputs change, while maintaining governance.*
OR-11: *track reliability metrics: success rate, retry/rollback frequency, MTTR, end-to-end latency.*

Naive re-planning ("something changed upstream, re-run every descendant") wastes work and, worse,
re-opens approvals that did not need to change. Naive metrics (counters incremented in the code)
drift from what actually happened, and can't be recomputed or audited.

## Decision

### Re-planning: provenance plus content hashes
- Every `Artifact` records `derivedFrom`, the exact `stage@version#hash` of each input its agent
  **actually read** (captured by `StageContext`, not declared by the agent).
- An output is **stale** when any input's *current* hash differs from the recorded one. Each
  scheduling pass sweeps the graph and emits `StageInvalidated` for stale accepted stages
  (back to `PENDING`, fresh retry budget).
- Only **direct** provenance is checked. A stage further down is re-evaluated only after its own
  input re-runs. If that re-run reproduces identical content, the hashes match and the cascade
  stops (**early cutoff**, as in build systems like Bazel).
- An output computed from an input that changed **while it was running** is discarded on arrival,
  never accepted. Re-planning is not a failure: no retry budget is consumed.
- **Governance stays intact:** an invalidated stage's open approval is withdrawn. Its new output
  has a new hash, so any approval must be given again. Human revisions go through the same
  policies as agent output (a pasted secret is blocked, a schema change needs approval).
- The graph **structure** is fixed per workflow version. What is re-planned is content (e.g. the
  task breakdown a design agent produces). Changing the process mid-run would undermine change
  control; a new process is a new workflow version.

### Metrics: computed from the event log
- `ReliabilityMetrics.compute(events)` is a pure function. Each metric's definition is documented
  on `ReliabilityReport` and verified against hand-built logs with known timestamps.
- **MTTR** = mean time from a stage's first failed attempt to that stage's eventual success
  (recovery via retry or fallback). Unrecovered failures are counted separately rather than
  skewing the mean.
- Percentiles use nearest-rank. "No data" is `null`, never a misleading `0`.
- `MeteredRunEventStore` decorates the store to publish live Micrometer meters (`/actuator/metrics`),
  for dashboards and alerting, without the engine knowing about metrics.

## Consequences

- **+** Re-planning re-runs exactly the stages whose inputs changed. A test proves an unrelated
  descendant (`docs`) is untouched, and that identical re-run output stops the cascade.
- **+** Every re-planning rule is mutation-verified: version-instead-of-hash comparison, no sweep,
  no mid-flight check, and unchecked revisions each fail a specific test.
- **+** Metrics are reproducible from the audit trail, by definition.
- **−** Provenance tracks what an agent *read*, not what it *used*. An agent that reads an input
  and ignores it is still invalidated when that input changes. That is conservative, which is the
  safe direction.
- **−** A human-revised artifact has no provenance (it is authoritative). If its own upstream
  changes later, the revision is not invalidated automatically.
- **−** Metrics over all runs are O(total events) per request. At volume, maintain them as an
  incrementally updated projection (same code, applied per event).
