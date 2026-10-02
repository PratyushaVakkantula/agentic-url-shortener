package com.agentic.orchestration.engine;

public class UnknownRunException extends RuntimeException {

    public UnknownRunException(String runId) {
        super("No workflow run with id '" + runId + "'");
    }
}
