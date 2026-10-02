package com.agentic.orchestration.definition;

/** A workflow graph is invalid (unknown dependency, cycle, duplicate stage...). Raised at build time. */
public class WorkflowDefinitionException extends RuntimeException {

    public WorkflowDefinitionException(String message) {
        super(message);
    }
}
