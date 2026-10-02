package com.agentic.orchestration.model;

public enum StageStatus {
    /** Waiting for dependencies. */
    PENDING,
    RUNNING,
    SUCCEEDED,
    FAILED,
    /** Not run because an upstream stage failed or the run stopped dispatching. */
    SKIPPED;

    public boolean isTerminal() {
        return this == SUCCEEDED || this == FAILED || this == SKIPPED;
    }
}
