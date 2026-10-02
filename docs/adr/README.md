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
