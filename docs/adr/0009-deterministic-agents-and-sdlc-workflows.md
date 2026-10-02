# ADR-0009: Deterministic agents and the three SDLC workflows

**Status:** Accepted

## Context

The brief asks for agents that understand requirements, decompose work, reason about an existing
codebase and produce engineering outputs, across greenfield, brownfield and ambiguous scenarios.
A-8 chose deterministic agents over LLM calls (runnable without keys, reproducible, testable).
The risk of that choice is agents that are hollow scripts. This ADR records how that is avoided.

## Decision

**Real logic where it is feasible; transparent rules elsewhere; one contract for both.**

| Agent | Technique |
|---|---|
| `requirements-analyst` | Rule-based NLP: extracts acceptance criteria (must/should/shall), detects vague qualities from a dictionary (fast, reliable, scalable, secure…), and pairs **each** ambiguity with a clarifying question and a concrete, testable default assumption. Classifies WELL_DEFINED / AMBIGUOUS. It does not guess greenfield vs brownfield, because that depends on the codebase. |
| `impact-analyzer` | **Static analysis of the actual repository**: indexes types, modules, layers, imports plus same-package references, REST endpoints, `@Table`s, migrations and tests. Scores types against keywords (name hits weigh 10× body mentions), picks the most relevant **module** (so "limit" does not drag in rate limiting), expands the blast radius over reverse dependencies, adds the entities seeds operate on, and orders tables by entity centrality. |
| `architect` | Brownfield: modify seeds; new persisted data becomes a **nullable** column through the next migration. Greenfield: a new module in the house layering. Quality assumptions become concrete design measures, so revising assumptions visibly re-plans the design. |
| `implementation-planner` | Proposed changeset plus a task DAG ordered by layer dependencies. Never applies changes. |
| `test-planner` | One test per acceptance criterion. A criterion that is vague *and* not made measurable by an assumption is **uncovered**, and the exit gate fails the stage. |
| `security-reviewer` | Design and changeset checklist (input validation, authorization, migration safety, security config, sensitive data). A HIGH finding blocks the release stage's entry gate. |
| `tech-writer`, `release-manager` | Changelog, API notes, ADR draft; semver bump, readiness checklist, rollout and rollback plan. |
| `impact-checklist` (fallback) | Used when static analysis is impossible (no source tree). Same output schema, claims nothing, risk HIGH: degrades to "ask a human", never to a guess. |

Every agent records its decisions with a rationale. The context records which artifact versions
it read, which gives lineage.

**Workflows** share one delivery pipeline (design → implementation → test-plan ∥ security-review →
docs → release\*) and differ before design: greenfield goes straight in, brownfield runs impact
analysis, and ambiguous adds a clarification checkpoint\* *before* impact analysis, so nothing is
built on unconfirmed assumptions. (\* = human approval.)

## Consequences

- **+** Scenarios are reproducible end to end in tests (`ScenarioIntegrationTest`), including
  static analysis of this repository, which takes about 0.1 s.
- **+** Swapping in an LLM for any agent is a local change behind the same `Agent` interface,
  with gates, policies, approvals and lineage unchanged around it.
- **−** Rule-based language understanding is shallow. It finds the vague terms it knows, and
  synonyms outside the dictionary are missed. The human checkpoint is the backstop; an LLM agent
  is the upgrade path.
- **−** Regex-based indexing is not a compiler: it over-approximates same-package references and
  ignores reflection and wiring by configuration. For impact analysis, over-approximation is the
  safe direction.
- **−** The impact heuristics (module focus, centrality) are generic but tuned on one codebase.
  They are tested on two different requirements (link limits → shortener, rate limiting →
  platform) to guard against overfitting.
