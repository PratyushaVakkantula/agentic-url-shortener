# ADR-0005: Orchestration engine: one actor per run, event-sourced state

**Status:** Accepted

## Context

The orchestrator must run a dependency graph of SDLC stages with parallel branches and
fan-in joins (OR-1–3). Later steps add human approvals that pause a run for hours,
retries, rollback, safe-stop, re-planning and crash recovery (OR-5–8, OR-12, OR-15). Every
transition must be auditable (OR-10).

Two common designs and their problems:

- **Shared mutable state guarded by locks**, with worker threads updating the run directly. Every
  new feature (approval arrives while a retry fires while stop is requested) multiplies the
  interleavings to reason about. Audit is a separate write that can disagree with state.
- **A linear task chain** (`for stage in stages: run(stage)`). Simple, but it cannot express parallel
  branches, joins, pauses or re-planning. The brief explicitly asks for more than this.

## Decision

1. **Explicit DAG, validated at build time** (`WorkflowDefinition`): unknown or self
   dependencies, duplicates and cycles are rejected, and cycles are reported with their path. It
   precomputes topological order and ancestor/descendant sets.
2. **One actor per run** (`RunCoordinator`): a single virtual thread owns the run and processes
   signals from a mailbox one at a time. Agents run concurrently on a shared virtual-thread
   pool and *post* their outcome back; they never touch run state.
3. **Event sourcing.** Each change is an immutable `RunEvent`, appended to the `RunEventStore`
   **before** it is applied to `RunState` (write-ahead). `RunState` is a pure fold over events,
   and the `switch` over the sealed event type is exhaustive, so a new event type cannot be
   forgotten.
4. **Gates.** Entry gates run before dispatch (failure: the stage cannot start, the agent never
   runs). Exit gates check the output (failure: the attempt fails, but the output is kept for
   audit). Gates **fail closed**: an exception counts as a failed gate.
5. **Context isolation and observed lineage.** An agent can read only its ancestors' artifacts.
   `StageContext` records what was actually read, and those exact `stage@version#hash`
   references are attached to the agent's decisions.
6. **Fail fast** (for now): after a failure, nothing new starts. In-flight stages complete and are
   recorded, and pending stages are skipped. Retries, fallback and rollback refine this in the
   governance step.

## Consequences

- **+** No locks on workflow state and no races between parallel stages, by construction.
- **+** Audit trail and state cannot diverge: state *is* the replayed audit trail
  (`replayingTheEventLogReproducesTheLiveState`). Crash recovery becomes "replay, then continue".
- **+** New behaviours (approval, stop, re-plan) are new signals and events, not new
  synchronisation.
- **+** The autonomy boundary is enforced by the type system *and* by ArchUnit: agent code cannot
  depend on the engine, state or event packages.
- **−** A run's throughput is bounded by one coordinator thread. That is irrelevant here, since
  coordination work is microseconds against seconds-to-hours of agent work.
- **−** Event schemas become a compatibility contract once persisted; changing an event type needs
  upcasting. That is the standard event-sourcing cost, accepted for the audit and recovery
  benefits.
- **−** Fail-fast is conservative: an independent branch is not started after an unrelated failure.
  This is deliberate for a governed SDLC (do not keep spending effort on a run that cannot ship).
