package com.agentic.orchestration.governance;

/** Ordered by severity; the engine acts on the most severe outcome across all policies. */
public enum PolicyOutcome {
    ALLOW,
    /** Output may proceed only after a human approves it. */
    REQUIRE_APPROVAL,
    /** Output is rejected and the run is safe-stopped for investigation. */
    BLOCK
}
