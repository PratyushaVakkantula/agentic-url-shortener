package com.agentic.shortener.domain;

/** The request is well-formed JSON but violates a business rule (URL safety, alias, expiry). */
public class InvalidLinkRequestException extends ShortenerException {

    private final String field;

    public InvalidLinkRequestException(String errorCode, String field, String message) {
        super(errorCode, message);
        this.field = field;
    }

    public String field() {
        return field;
    }
}
