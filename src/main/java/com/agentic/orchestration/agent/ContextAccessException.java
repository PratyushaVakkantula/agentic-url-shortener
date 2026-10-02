package com.agentic.orchestration.agent;

/** An agent tried to read an artifact outside its declared data dependencies. */
public class ContextAccessException extends RuntimeException {

    public ContextAccessException(String message) {
        super(message);
    }
}
