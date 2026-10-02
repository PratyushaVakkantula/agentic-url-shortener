package com.agentic.orchestration.event;

/** Why a stage attempt failed. Drives retry decisions in the governance layer. */
public enum FailureKind {
    /** Preconditions not met; retrying the same inputs cannot help. */
    ENTRY_GATE,
    /** Output rejected by an exit gate. */
    EXIT_GATE,
    /** The agent threw. */
    AGENT_ERROR,
    /** The agent read outside its declared dependencies: a defect in the agent, never retried. */
    CONTEXT_VIOLATION
}
