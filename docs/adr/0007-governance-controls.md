# ADR-0007: Governance: approvals, policies, retries, rollback, safe-stop

**Status:** Accepted

## Context

The brief's principle: *agents execute under defined autonomy boundaries; humans own oversight,
approvals, and final quality.* That requires controls that are enforced by the engine, not
promised by agents: human checkpoints, policy guardrails (security, compliance, change control),
bounded retries, fallback, rollback, timeouts and safe-stop (OR-5–9, OR-13, OR-14, OR-16).

## Decision

### Human approval checkpoints
- A stage needs approval if it is declared high-impact (`requiresApproval(reason)`) **or** any
  policy returns `REQUIRE_APPROVAL`. The stage's output is held in `AWAITING_APPROVAL`, and
  downstream stages cannot start.
- **Bound to content.** The approver must submit the SHA-256 of the artifact they reviewed. A
  mismatch returns `409 STALE_APPROVAL`. An approval can never be applied to content the
  approver did not see.
- **Separation of duties.** A run's initiator cannot approve its checkpoints, even when they
  hold the APPROVER role. The initiator always comes from authentication, never the request body.
- **Expiry.** An undecided approval expires (default 24 h) and counts as a rejection.
- **Request/reply through the mailbox.** Decisions are validated by the run's single-writer
  coordinator, so the checks cannot race with the run's own progress.

### Policy guardrails
`PolicyEngine` runs every `Policy` on every stage output, and **all** results (including ALLOW)
are recorded so the audit trail proves each guardrail ran. The most severe outcome wins.

| Policy | Category | Outcome |
|---|---|---|
| `secret-leak` | Security | BLOCK on credentials (AWS/GitHub/Slack tokens, private keys, hard-coded passwords). Reports the rule, **never the value**. |
| `personal-data` | Compliance | REQUIRE_APPROVAL on SSNs and Luhn-valid card numbers |
| `dependency-license` | Compliance | BLOCK on denied licenses (AGPL, GPL, SSPL, BUSL); unknown license → REQUIRE_APPROVAL |
| `change-control` | Change control | BLOCK when editing or deleting an applied migration; REQUIRE_APPROVAL for new migrations, security config changes or a large blast radius |

A policy that throws produces REQUIRE_APPROVAL. That fails safe without one buggy rule halting
all work.

### Failure handling
- **Retries.** Only for retryable kinds (agent error, exit gate, timeout). Bounded attempts with
  exponential backoff. Never for entry gates, policy blocks or human rejections, where retrying
  cannot change the answer.
- **Fallback.** One attempt by a secondary agent after the primary's retries are exhausted.
- **Timeouts.** Per attempt. The worker is interrupted, and every outcome carries its attempt
  number, so **late results of a timed-out attempt are discarded**.
- **Rollback.** On run failure, succeeded stages with a `Compensation` are undone **in reverse
  completion order**, one at a time. Failures are recorded as `ROLLBACK_FAILED` ("manual cleanup
  required") and do not abort the rest. The plan is an event, so a crash mid-rollback resumes it.
- **Safe-stop.** Triggered by an operator (ADMIN) or a BLOCK policy. No new work starts, agents
  see `isCancelled()`, retries and approvals are withdrawn, and the run ends `STOPPED`.
  **Safe-stop does not roll back:** a stopped run is preserved for investigation.

## Consequences

- **+** Every control is an engine invariant, covered by a test that a mutation of the rule makes
  fail. Five rules were verified this way: separation of duties, hash binding, late-outcome
  discard, policy block, rollback.
- **+** All decisions are events, so they are auditable, replayable, and survive restarts. An
  open approval can be decided on a newly deployed process.
- **−** Policies inspect outputs by convention (`changes`, `dependencies`). An agent that
  structures its output differently would not be checked by those rules. Mitigation: agents are
  ours, and the content-scanning policies (`secret-leak`, `personal-data`) work on any shape.
- **−** One approval per stage attempt. Multi-party approval (two approvers for production) would
  be a quorum field on the approval request. It is not needed for this scope.
- **−** Backoff has no jitter. That's fine with one coordinator per run; add jitter if retries ever
  target a shared external service.
