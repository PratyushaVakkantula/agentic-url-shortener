# ADR-0001: Modular monolith with enforced module boundaries

**Status:** Accepted

## Context

The system has two very different parts. The URL shortener is a latency-sensitive, read-heavy
HTTP service. The orchestration engine is a long-running, stateful, governance-heavy
workflow runtime. They share cross-cutting needs: security, error format, request
correlation, configuration.

Options considered:

1. **Microservices** (shortener service + orchestrator service + gateway). Independent
   scaling and deployment, but it brings network calls, distributed auth, service discovery,
   and multi-repo or multi-artifact CI. That is a lot of operational surface for a prototype
   with one team and no independent scaling requirement yet.
2. **Single unstructured application.** Fastest to start, but the two concerns would
   entangle and become expensive to separate later.
3. **Modular monolith.** One deployable, internally split into modules with explicit,
   machine-checked dependency rules.

## Decision

Option 3. One Spring Boot application with three top-level packages:

| Module | Responsibility | May depend on |
|--------|----------------|---------------|
| `com.agentic.platform` | Security, web plumbing (request ids, error format), shared config, `Clock` | nothing internal |
| `com.agentic.shortener` | Links, redirects, analytics | `platform` |
| `com.agentic.orchestration` | Workflow engine, agents, governance | `platform` |

`shortener` and `orchestration` do not depend on each other. The brownfield agent reasons
about the shortener by reading its **source files** (static analysis), not by linking against
its classes, so the engine stays independent of what it analyzes.

The rules are enforced by `ArchitectureTest` (ArchUnit) on every build, so CI fails on a
boundary violation.

## Consequences

- **+** One build, one deploy, in-process calls, simple local setup (`./mvnw spring-boot:run`).
- **+** Splitting later is cheap. Each module is already self-contained, so extracting
  `shortener` into its own service means moving a package and adding an HTTP client.
- **+** Architecture is executable. Diagrams cannot silently drift from the code.
- **−** Modules cannot be scaled independently. A redirect traffic spike and a heavy workflow
  share one JVM. Virtual threads soften this for I/O-bound work, but it is the main trigger
  for extracting the shortener (see engineering summary).
- **−** A shared database. Each module owns its own tables and must not query the other's.
  This is enforced by convention plus package rules, not by separate schemas.
