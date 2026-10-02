package com.agentic.orchestration.model;

public enum RunStatus {
    RUNNING,
    SUCCEEDED,
    FAILED;

    public boolean isTerminal() {
        return this != RUNNING;
    }
}
