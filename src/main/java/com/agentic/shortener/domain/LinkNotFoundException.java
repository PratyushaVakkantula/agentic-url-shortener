package com.agentic.shortener.domain;

public class LinkNotFoundException extends ShortenerException {

    public LinkNotFoundException(String code) {
        super("LINK_NOT_FOUND", "No short link exists for code '" + code + "'.");
    }
}
