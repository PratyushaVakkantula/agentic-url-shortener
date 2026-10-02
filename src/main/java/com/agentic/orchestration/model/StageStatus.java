package com.agentic.orchestration.model;

public enum StageStatus {
    /** Waiting for dependencies. */
    PENDING,
    RUNNING,
    /** An attempt failed; the next one is scheduled after a backoff. */
    WAITING_RETRY,
    /** Output produced and checked; waiting for a human decision. */
    AWAITING_APPROVAL,
    SUCCEEDED,
    FAILED,
    /** Not run, or abandoned, because the run failed or stopped. */
    SKIPPED,
    /** Succeeded, then undone by its compensation during rollback. */
    ROLLED_BACK,
    /** Compensation failed; needs manual cleanup (surfaced in the run view). */
    ROLLBACK_FAILED;

    /** Work in flight or waiting: the run cannot complete while any stage is in one of these. */
    public boolean isActive() {
        return this == RUNNING || this == WAITING_RETRY || this == AWAITING_APPROVAL;
    }
}
