package com.agentic.orchestration.model;

public enum RunStatus {
    RUNNING,
    SUCCEEDED,
    /** A stage failed terminally; completed stages with compensations were rolled back. */
    FAILED,
    /** Halted by safe-stop (operator or blocking policy); state preserved, nothing rolled back. */
    STOPPED;

    public boolean isTerminal() {
        return this != RUNNING;
    }
}
