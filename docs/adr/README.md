# Architecture Decision Records

Each significant, hard-to-reverse decision is recorded here in the format from
[Michael Nygard](https://cognitect.com/blog/2011/11/15/documenting-architecture-decisions):
**Context → Decision → Consequences**. ADRs are never edited after acceptance. A changed
decision gets a new ADR that supersedes the old one.

| # | Title | Status |
|---|-------|--------|
| [0001](0001-modular-monolith.md) | Modular monolith with enforced module boundaries | Accepted |
| [0002](0002-random-short-codes.md) | Random short codes with database-enforced uniqueness | Accepted |
| [0003](0003-url-safety-without-dns.md) | URL safety validation is syntactic; no DNS resolution | Accepted |
| [0004](0004-redirect-hot-path.md) | Redirect hot path: read-through cache and asynchronous click recording | Accepted |
| [0005](0005-orchestration-engine-actor-event-sourced.md) | Orchestration engine: one actor per run, event-sourced state | Accepted |
| [0006](0006-durable-event-store-and-recovery.md) | Durable event store and crash recovery | Accepted |
| [0007](0007-governance-controls.md) | Governance: approvals, policies, retries, rollback, safe-stop | Accepted |
| [0008](0008-replanning-and-reliability-metrics.md) | Hash-based re-planning and log-derived reliability metrics | Accepted |
| [0009](0009-deterministic-agents-and-sdlc-workflows.md) | Deterministic agents and the three SDLC workflows | Accepted |
| [0010](0010-durability-of-committed-events.md) | Committed events must survive a hard crash | Accepted |
