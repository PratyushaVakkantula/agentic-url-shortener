# Engineering summary

## 1. Plan and rationale

The brief weighs **agentic orchestration** most heavily. The plan therefore invested in a governed
engine that is correct by construction, and used a realistic URL shortener as the system it
builds on and reasons about.

| Step | Outcome | Why in this order |
|---|---|---|
| 1 | [Requirements analysis](requirements.md): FR/NFR/OR IDs, 13 ambiguity decisions, task DAG | Interpret before building, as the brief asks |
| 2 | Platform: RBAC, RFC 9457 errors, request ids, Flyway, CI, ArchUnit | Cross-cutting rules first, so later code is born compliant |
| 3–4 | Shortener: domain, URL safety, API, cache, async clicks, rate limit | A production-grade target for the orchestrator to reason about |
| 5 | Engine core: DAG, gates, actor-per-run, event sourcing, lineage | The foundation every governance feature plugs into |
| 6 | Durable event store, crash recovery, REST API | State must survive restarts before humans wait on it |
| 7 | Governance: approvals, policies, retries, fallback, timeouts, rollback, safe-stop | The "controlled autonomy" core |
| 8 | Re-planning (hash provenance), reliability metrics | Change management and observability |
| 9 | Agents + greenfield/brownfield/ambiguous workflows | Real behaviour on top of a proven engine |
| 10 | Live end-to-end demo, including `kill -9` | Verify the running system, not only the tests |
| 11 | Documentation, with docs consistency enforced by tests | Deliverables that stay true |

Each step was its own commit with tests, so the history reads as a reasoned progression.

## 2. Artifacts

| Artifact | Location |
|---|---|
| Working prototype | `./mvnw spring-boot:run`, then `./scripts/demo.sh` |
| Requirements and decisions | [requirements.md](requirements.md) |
| Architecture overview | [architecture.md](architecture.md) |
| Decision records | [adr/](adr/README.md) (10 ADRs) |
| Scenario walkthroughs | [scenarios.md](scenarios.md) |
| Requirement → test matrix | [traceability.md](traceability.md) |
| API contract | `/v3/api-docs`, Swagger UI at `/swagger-ui.html` |
| Schema | `src/main/resources/db/migration/V1–V4` |
| Tests | 247 tests; 94.6% line, 80.4% branch coverage (JaCoCo, `target/site/jacoco`) |
| CI | `.github/workflows/ci.yml` (build, all tests, ArchUnit, coverage report) |

## 3. Validation strategy

The approach treats a passing test as a claim that needs evidence. Several layers were used:

| Layer | What | Example |
|---|---|---|
| Unit | Pure rules, exhaustive cases | `UrlSafetyValidatorTest` (55 cases incl. `0177.0.0.1`), `PoliciesTest`, `TokenBucketTest` |
| Concurrency | Tests that can **only** pass if the property holds | A barrier forcing two branches to run simultaneously; 8 threads racing for one alias → exactly one winner |
| Integration | Real Spring context, real database, HTTP via MockMvc | `ShortenerApiIntegrationTest`, `WorkflowApiIntegrationTest`, `RecoveryIntegrationTest` |
| Scenario | Whole workflows with real agents, policies, approvals | `ScenarioIntegrationTest` (all three scenarios) |
| Architecture | Design rules executable in the build | `ArchitectureTest` (11 rules, incl. the agent autonomy boundary) |
| **Mutation checks** | Remove a rule → confirm its test fails | 13 rules checked (marked 🧬 in the [matrix](traceability.md)); 2 initially *survived* and led to better tests |
| **Process-level** | Kill a real JVM, check committed data | `DurabilityTest` (`SIGKILL` a child process) |
| Determinism | Injected `Clock` everywhere (ArchUnit-enforced); tests run in `America/New_York` | Catches local-time assumptions that UTC CI runners hide |
| Stability | Repeat timing-sensitive suites | Orchestration suites run 6–10× in a row with no flakes |
| Docs | Docs claims are tests | `DocumentationConsistencyTest`: every test and method named in the matrix exists; no broken links |
| **Live demo** | The running app via HTTP, including `kill -9` | `scripts/demo.sh` |

### Defects found by this process (all fixed, each with a regression test)

| Found by | Defect | Fix |
|---|---|---|
| Live demo | Analytics bucketed clicks by server-local day (00:42Z counted as the previous day in New York) | Store the UTC day computed in Java (V2) |
| Probe test | V2's backfill had the same flaw: H2 casts in the session time zone even with `AT TIME ZONE` | V3 data fix (V2 left immutable) + `MigrationTest` |
| JDBC probe | Hibernate's legacy `java.sql.Date` path shifted dates by a day when JVM zone ≠ session zone | `java_time_use_direct_jdbc` |
| Slow suite (142 s) | Non-fair lock: click worker starved `flush()` for up to **66 s** | Fair lock; regression test fails in 2.5 s without it |
| ArchUnit | Service result type leaked repository projections to the API layer | Service-level value types |
| Mutation check | A starvation regression test did not reproduce the bug | Rewrote under the conditions that trigger it |
| Mutation check | Mid-flight staleness check was "redundant" by outcome | Kept it; asserted its real guarantee (stale output never accepted) |
| Scenario test | Impact analysis omitted the entity; picked `click_event` over `short_link` | Include seed entities; order tables by centrality |
| Review | Analyst guessed greenfield vs brownfield (wrongly) | Report only what it can judge: WELL_DEFINED / AMBIGUOUS |
| Review | Duplicate in `Set.of` stopwords: crash on first use | Removed; checked for duplicates |
| **Live `kill -9`** | **H2 lost committed events on a hard kill (0/50 rows)** | `WRITE_DELAY=0` + `DurabilityTest` ([ADR-0010](adr/0010-durability-of-committed-events.md)) |
| Live demo | Greenfield module named `code` ("QR" dropped as short) | Keep acronyms |
| Live demo | Demo script broke on macOS bash 3.2 | Portable empty-array idiom |

## 4. Risks, trade-offs and mitigations

| Decision | Benefit | Cost / risk | Mitigation or upgrade path |
|---|---|---|---|
| Modular monolith | One deploy, simple ops, in-process calls | Modules cannot scale independently | Boundaries are machine-checked; extracting a module is a package move |
| Actor per run | No locks or races by construction | One coordinator thread per run | Coordination is µs vs seconds-to-hours of agent work |
| Event sourcing | Audit = state; replay; recovery | Event schemas become a contract | `schema_version` + upcasters; never rewrite history |
| Deterministic agents | Reproducible, testable, no API keys | Shallow language understanding (dictionary-bound) | Human checkpoint is the backstop; LLM agents fit the same `Agent` interface |
| Regex-based code index | Fast (~0.1 s), dependency-free | Not a compiler (reflection, config wiring invisible) | Over-approximates, which is the safe direction; a fallback asks a human |
| Fail-fast runs | Do not invest in a run that cannot ship | Independent branches are not started after a failure | Deliberate for a governed SDLC |
| Policies by output convention | Simple, inspectable | An agent structuring output differently evades structural policies | Content-scanning policies are shape-independent; agents are in-repo |
| H2 file database | Zero setup | Not power-loss durable; single process | PostgreSQL via `DB_URL` (portable SQL, Flyway) |
| In-memory cache and rate limiter | Fast, simple | Per instance (N instances → N× limit, stale deactivation ≤ TTL) | Redis for both when scaling out |
| Async click recording | Redirects never wait on analytics | Clicks in the queue are lost on `kill -9`; analytics ~200 ms behind | Bounded, visible loss; Kafka for billing-grade counting |

## 5. Assumptions

All assumptions are recorded as A-1 to A-13 in [requirements.md](requirements.md). The most
consequential are:

- Agents produce **proposals**; nothing is applied to a real repository (A-9). Re-running an
  agent is therefore side-effect free, which crash recovery relies on.
- One engine instance per database (ADR-0006).
- Link creation is anonymous; per-user link ownership is out of scope (A-7).
- "Click" = every successful redirect, no unique-visitor dedupe (A-3, privacy).

## 6. Limitations

- **Single instance:** recovery, cache and rate limiting assume one process. The scale-out path
  is a lease column for run ownership, plus Redis.
- **Agents are rule-based:** synonyms outside the vague-term dictionary are missed; designs follow
  templates; implementation produces a changeset proposal, not code.
- **Graph structure is static** per workflow version. Re-planning changes content, not shape.
- **Approvals are single-approver;** no quorum for production.
- **Metrics over all runs are O(events)** per request; maintain them as a projection at volume.
- **Demo identity:** in-memory users with HTTP Basic; production would use OIDC.
- **Raw Unicode (IDN) hosts** must be sent in punycode.

## 7. What I would do next

1. An LLM-backed requirements analyst and architect behind the existing `Agent` interface, with
   the deterministic versions as fallbacks (the fallback mechanism already exists).
2. A PostgreSQL profile with Testcontainers in CI, plus a lease-based multi-instance coordinator.
3. A small UI showing the run graph, the pending approvals, and the diff an approver is about to
   sign (the hash binding is already there).
4. Multi-party approvals (quorum) for production releases.
5. Apply the changeset to a branch and open a pull request, still behind the same human approval.
