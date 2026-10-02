package com.agentic.orchestration.event;

/** Why a stage attempt failed. Drives retry and fallback decisions. */
public enum FailureKind {
    /** Preconditions not met; retrying the same inputs cannot help. Not retried. */
    ENTRY_GATE(false),
    /** Output rejected by an exit gate. Retried. */
    EXIT_GATE(true),
    /** The agent threw. Retried. */
    AGENT_ERROR(true),
    /** The attempt exceeded its time limit and was cancelled. Retried. */
    TIMEOUT(true),
    /** The agent read outside its declared dependencies: a defect, never retried. */
    CONTEXT_VIOLATION(false),
    /** A guardrail blocked the output. Deterministic, so not retried; the run is safe-stopped. */
    POLICY_BLOCKED(false),
    /** A human rejected the output. */
    APPROVAL_REJECTED(false),
    /** No human decided in time. */
    APPROVAL_EXPIRED(false);

    private final boolean retryable;

    FailureKind(boolean retryable) {
        this.retryable = retryable;
    }

    public boolean retryable() {
        return retryable;
    }
}
