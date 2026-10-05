# Agentic URL Shortener

[![CI](https://github.com/PratyushaVakkantula/agentic-url-shortener/actions/workflows/ci.yml/badge.svg)](https://github.com/PratyushaVakkantula/agentic-url-shortener/actions/workflows/ci.yml)

A production-style **URL shortener**, plus an **agentic SDLC orchestrator** that takes a
requirement through requirements → impact analysis → design → implementation → test plan ∥
security review → docs → release, as a **governed, event-sourced dependency graph**.

Agents propose; humans approve. Every decision is audited, survives crashes, and is re-planned
when its inputs change.

> Java 21 · Spring Boot 4 · H2 (PostgreSQL-ready) · Flyway · 253 tests, 94.5% line coverage · 10 ADRs

## Start here: a 15-minute review

| Time | Do this | What it shows |
|---|---|---|
| 3 min | `./mvnw spring-boot:run`, then in a second terminal `./scripts/demo.sh` | Every scenario end to end through the HTTP API: greenfield, brownfield, a bug fix, an ambiguous requirement clarified by a human and re-planned, governance refusals, metrics |
| 4 min | Read [docs/scenarios.md](docs/scenarios.md) | What the agents decided and why, with the real output |
| 5 min | Open three files: [RunCoordinator.java](src/main/java/com/agentic/orchestration/engine/RunCoordinator.java) (the class comment shows the whole governance flow), [ArchitectureTest.java](src/test/java/com/agentic/ArchitectureTest.java) (`agentsCannotReachEngineStateOrEvents`), [ImpactAnalysisAgent.java](src/main/java/com/agentic/orchestration/agents/ImpactAnalysisAgent.java) (static analysis of this repo) | The engine design, the enforced autonomy boundary, real codebase reasoning |
| 3 min | Read [engineering-summary.md §3](docs/engineering-summary.md#3-validation-strategy) | How the work was verified, and the defects that verification found |

Optional: the [crash-recovery demo](#crash-recovery-demo-about-1-minute) (`kill -9` while a run waits for approval) takes one more minute.

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
  types, dependencies, endpoints, tables, migrations, tests, and the **data flows** from each
  entry point to the tables it reaches. Enhancements, bug fixes and refactors are told apart and
  handled differently (a bug fix is reproduced first and ships as a patch).
- **Shortener.**
  - SSRF-aware URL validation, including `0177.0.0.1`-style IP spellings.
  - Cached redirect path with asynchronous, batched click analytics.
  - Token-bucket rate limiting, RBAC, RFC 9457 errors, OpenAPI.

## Quick start

### 1. Prerequisites

| Need | Check | Install if missing |
|---|---|---|
| **JDK 21** (the version it is built and tested with) | `java -version` → `21…` | macOS: `brew install openjdk@21`, then `export JAVA_HOME=/opt/homebrew/opt/openjdk@21`; any OS: [Adoptium Temurin 21](https://adoptium.net) or `sdk install java 21-tem` ([SDKMAN](https://sdkman.io)) |
| `curl` and `jq` (demo script only) | `jq --version` | macOS 15+ ships both; otherwise `brew install jq` / `apt install jq` |
| Free port 8080 | – | or pick another port (see Troubleshooting) |

Maven is **not** needed: `./mvnw` downloads the right version on first use (needs internet once).
On **Windows** use `mvnw.cmd` instead of `./mvnw`, and run the demo script from Git Bash or WSL.

### 2. Build and test

```bash
./mvnw verify
```
Compiles, runs all 253 tests and the architecture rules, and writes a coverage report to
`target/site/jacoco/index.html`. The first run downloads dependencies, which takes a few minutes; after that a full build takes
under a minute (about 25 s measured).

### 3. Run the app

```bash
./mvnw spring-boot:run
```
Ready when the log shows `Started AgenticUrlShortenerApplication`. Then:
- Swagger UI: http://localhost:8080/swagger-ui.html (use **Authorize** with a demo user below)
- Health: http://localhost:8080/actuator/health

Data is stored in `./data/` (H2 file database), so it survives restarts. Stop the app with `Ctrl+C`.

### 4. Run the end-to-end demo

In a **second terminal**, with the app running:

```bash
./scripts/demo.sh
```
Runs everything through the public HTTP API: the shortener, the scenarios (greenfield,
brownfield enhancement, brownfield bug fix, ambiguous with a human revision), the governance
refusals, and the metrics. It takes
about 5 seconds, and it is safe to run repeatedly.

Run a single section by passing its name, for example `./scripts/demo.sh brownfield`. Sections:
`shortener`, `greenfield`, `brownfield`, `bugfix`, `ambiguous`, `governance`, `metrics`.

### Demo users

| User | Password | Role | Can |
|---|---|---|---|
| alice | `alice-pass` | REQUESTER | start runs, revise stage outputs |
| bob, carol | `bob-pass`, `carol-pass` | APPROVER | approve or reject checkpoints, never on runs they started |
| admin | `admin-pass` | ADMIN | everything, plus deactivate links, safe-stop runs, actuator metrics |

Passwords are stored as bcrypt hashes. Anonymous users can create and follow short links.

### Crash-recovery demo (about 1 minute)

Shows a run surviving a hard kill while it waits for a human.

1. Start a run and leave it waiting at a checkpoint (the script prints the run id):
   ```bash
   ./scripts/demo.sh pause
   ```
2. Crash the server:
   ```bash
   pkill -9 -f AgenticUrlShortenerApplication
   ```
3. Start it again with `./mvnw spring-boot:run`. The log shows `Resumed run <id> (brownfield-change v1)`.
4. Decide the **same** open approval on the new process, and finish the run:
   ```bash
   ./scripts/demo.sh approve <run-id-from-step-1>
   ```

### Troubleshooting

| Symptom | Cause | Fix |
|---|---|---|
| `Unable to locate a Java Runtime` (macOS), `java: command not found`, or a `JAVA_HOME` error | No JDK found | Install JDK 21 (see Prerequisites); check with `java -version` |
| `release version 21 not supported` | An older JDK is active | Point `JAVA_HOME` at JDK 21 |
| `Port 8080 was already in use` | Something else uses 8080 | `SERVER_PORT=8081 ./mvnw spring-boot:run`, then `BASE=http://localhost:8081 ./scripts/demo.sh` |
| `Database may be already in use` | A second copy of the app is running against `./data` | Stop the other copy (only one instance per database) |
| Demo prints `No app at http://localhost:8080` | App not started yet | Start it and wait for `Started AgenticUrlShortenerApplication` |
| `jq: command not found` | Demo dependency missing | Install `jq` |
| Want a clean slate | – | Stop the app and delete `./data/` |

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
scripts/demo.sh                    end-to-end demo (and crash-recovery demo) against a running instance
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
