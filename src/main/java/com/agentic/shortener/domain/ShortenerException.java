package com.agentic.shortener.domain;

/**
 * Base for shortener business errors. {@link #errorCode()} is a stable, machine-readable
 * identifier that API clients can branch on; the message is for humans and may change.
 */
public abstract class ShortenerException extends RuntimeException {

    private final String errorCode;

    protected ShortenerException(String errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    public String errorCode() {
        return errorCode;
    }
}
