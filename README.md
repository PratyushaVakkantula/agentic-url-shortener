# Agentic URL Shortener

[![CI](https://github.com/PratyushaVakkantula/agentic-url-shortener/actions/workflows/ci.yml/badge.svg)](https://github.com/PratyushaVakkantula/agentic-url-shortener/actions/workflows/ci.yml)

A production-style **URL shortener**, plus an **agentic SDLC orchestrator** that takes a
requirement through requirements → impact analysis → design → implementation → test plan ∥
security review → docs → release, as a **governed, event-sourced dependency graph**.

Agents propose; humans approve. Every decision is audited, survives crashes, and is re-planned
when its inputs change.

> Java 21 · Spring Boot 4 · H2 (PostgreSQL-ready) · Flyway · 247 tests, 94.6% line coverage · 10 ADRs

## Highlights

- **Orchestration engine.**
  - Validated DAG with entry and exit gates.
  - Parallel branches on virtual threads, joined by fan-in.
  - One **actor per run**, so state changes have no locks and no races.
  - **Event-sourced:** the audit log *is* the state.
- **Governance.**
  - Human approvals **bound to the SHA-256 of the reviewed artifact**, never granted by the
    run's initiator, and expiring.
  - Policy guardrails: secret leaks, personal data, licenses, change control.
  - Bounded retries with backoff, fallback agents, per-attempt timeouts, reverse-order
    rollback, safe-stop.
- **Re-planning.** Artifacts record which input versions they were derived from. When a human
  revises an upstream output, only stages whose inputs **actually changed** re-run. Identical
  re-run output stops the cascade.
- **Crash recovery.** A run killed with `kill -9` while waiting for approval resumes on restart,
  and the same approval can be decided on the new process. Verified live, and by a test that
  `SIGKILL`s a JVM.
- **Brownfield reasoning.** The impact-analysis agent **statically analyses this repository**:
  types, dependencies, endpoints, tables, migrations, tests.
- **Shortener.**
  - SSRF-aware URL validation, including `0177.0.0.1`-style IP spellings.
  - Cached redirect path with asynchronous, batched click analytics.
  - Token-bucket rate limiting, RBAC, RFC 9457 errors, OpenAPI.

## Quick start

Prerequisites: **JDK 21**. For the demo script you also need `curl` and `jq`. Maven is not
needed, because the wrapper downloads it.

```bash
./mvnw verify
```
Builds the project, runs all 247 tests and the ArchUnit rules, and writes a coverage report to `target/site/jacoco/index.html`.

```bash
./mvnw spring-boot:run
```
Starts the app on http://localhost:8080. Swagger UI is at `/swagger-ui.html`.

```bash
./scripts/demo.sh
```
In a second terminal, runs the end-to-end demo: all three scenarios, the governance refusals, and the metrics.

Run one section with `./scripts/demo.sh brownfield`. Available sections are `shortener`,
`greenfield`, `brownfield`, `ambiguous`, `governance` and `metrics`.

### Demo users

| User | Password | Role | Can |
|---|---|---|---|
| alice | `alice-pass` | REQUESTER | start runs, revise stage outputs |
| bob, carol | `bob-pass`, `carol-pass` | APPROVER | approve or reject checkpoints, never on runs they started |
| admin | `admin-pass` | ADMIN | everything, plus deactivate links, safe-stop runs, actuator metrics |

Passwords are stored as bcrypt hashes. Anonymous users can create and follow short links.

### Try the crash recovery yourself

1. Start a brownfield run (`./scripts/demo.sh brownfield`) and stop the script at the first
   checkpoint, or start one with `curl`.
2. Kill the server with `kill -9 <pid>`.
3. Start it again with `./mvnw spring-boot:run`. The log shows `Resumed run … (brownfield-change v1)`.
4. `GET /api/v1/runs/{id}` shows the same pending approval. Approve it and the run completes.

## API at a glance

| Endpoint | Who | Purpose |
|---|---|---|
| `POST /api/v1/urls` | anyone (rate limited) | Create a short link (optional `customAlias`, `expiresAt`) |
| `GET /{code}` | anyone | 302 redirect; 404 unknown; 410 expired or deactivated |
| `GET /api/v1/urls/{code}` · `/analytics?days=N` | anyone | Metadata; clicks per UTC day, referrers, browsers |
| `DELETE /api/v1/urls/{code}` | ADMIN | Deactivate (soft delete) |
| `GET /api/v1/workflows[/{name}]` | authenticated | Workflow graphs with gates and governance per stage |
| `POST /api/v1/workflows/{name}/runs` | REQUESTER | Start a run → `202` + `Location` |
| `GET /api/v1/runs[/{id}]` | authenticated | Runs; full state with stages, gates, policies, approvals, artifacts, decisions |
| `POST /api/v1/runs/{id}/approvals/{approvalId}` | APPROVER | `{decision, artifactHash, comment}` |
| `POST /api/v1/runs/{id}/stages/{stageId}/revisions` | REQUESTER | Replace an output → re-planning |
| `POST /api/v1/runs/{id}/stop` | ADMIN | Safe-stop |
| `GET /api/v1/runs/{id}/events` | authenticated | Audit trail (every event, in order) |
| `GET /api/v1/metrics` · `/runs/{id}/metrics` | authenticated | Success rate, retry and rollback frequency, MTTR, latency, governance, re-planning |

Workflows: `greenfield-feature`, `brownfield-change`, `ambiguous-requirement`.
Every error is RFC 9457 JSON with a stable `errorCode` and a `requestId`.

## Documentation

| Document | Contents |
|---|---|
| [docs/requirements.md](docs/requirements.md) | Requirement analysis: FR/NFR/OR IDs, ambiguities → decisions, task plan |
| [docs/architecture.md](docs/architecture.md) | Components, orchestration model, control flow, data and security model (diagrams) |
| [docs/scenarios.md](docs/scenarios.md) | Greenfield, brownfield, ambiguous: decomposition, orchestration, validation (real outputs) |
| [docs/engineering-summary.md](docs/engineering-summary.md) | Plan and rationale, validation strategy, **defects found**, risks and trade-offs, assumptions, limitations |
| [docs/traceability.md](docs/traceability.md) | Every requirement → implementation → test (kept true by a test) |
| [docs/adr/](docs/adr/README.md) | 10 architecture decision records |

## Project layout

```
src/main/java/com/agentic/
├── platform/        security (RBAC), rate limiting, request ids, RFC 9457 errors, Clock, OpenAPI
├── shortener/       api · domain · repository · service (cache, click pipeline, URL safety)
└── orchestration/
    ├── definition/  workflow DAG, gates, stage policies (retry, timeout, fallback, compensation, approval)
    ├── engine/      WorkflowEngine, RunCoordinator (actor), content hashing
    ├── event/       sealed RunEvent hierarchy, JDBC event store, codec
    ├── state/       RunState (fold over events), RunView
    ├── governance/  PolicyEngine + policies
    ├── metrics/     ReliabilityMetrics, Micrometer decorator
    ├── agent/       Agent contract, read-only StageContext
    ├── agents/      SDLC agents incl. static impact analysis
    ├── workflows/   greenfield / brownfield / ambiguous definitions
    └── api/         REST controllers
src/main/resources/db/migration/   V1–V4 (append-only)
scripts/demo.sh                    end-to-end demo against a running instance
```

## Configuration

| Variable | Default | Notes |
|---|---|---|
| `DB_URL` | `jdbc:h2:file:./data/shortener;WRITE_DELAY=0` | For H2 file URLs **keep `;WRITE_DELAY=0`**: without it a hard kill can lose committed audit events ([ADR-0010](docs/adr/0010-durability-of-committed-events.md)). PostgreSQL works as-is (portable SQL, Flyway). |
| `DB_USER` / `DB_PASSWORD` | `sa` / empty | |
| `PUBLIC_BASE_URL` | `http://localhost:8080` | Used to build short URLs |
| `app.orchestration.approval-ttl` | `24h` | Open checkpoints expire after this |
| `app.orchestration.default-stage-timeout` | `10m` | Per-attempt limit |
| `app.rate-limit.rules[*]` | 30 creations/min/client | Declarative token-bucket rules |

## Design in one paragraph

A modular monolith (`platform`, `shortener`, `orchestration`) with boundaries enforced by
ArchUnit. Each workflow run is driven by a single-writer actor. Agents run in parallel on
virtual threads and post results to the actor's mailbox. Every change is appended to an
event log **before** it is applied, and run state is a pure fold over that log, so audit trail,
state and crash recovery are the same mechanism. Agents can read only their ancestors' artifacts
and the engine records what they read, which gives decision lineage and makes re-planning
precise. Governance is enforced by the engine, never left to agents. See
[docs/architecture.md](docs/architecture.md).

## Limitations

These are deliberate, and each is documented with its upgrade path in the
[engineering summary](docs/engineering-summary.md#6-limitations):
- Agents are deterministic and rule-based (no LLM calls).
- One instance per database.
- H2 by default (PostgreSQL-ready).
- Demo identities instead of OIDC.
