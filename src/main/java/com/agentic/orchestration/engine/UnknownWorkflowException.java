package com.agentic.orchestration.engine;

public class UnknownWorkflowException extends RuntimeException {

    public UnknownWorkflowException(String name) {
        super("No workflow named '" + name + "'");
    }
}
