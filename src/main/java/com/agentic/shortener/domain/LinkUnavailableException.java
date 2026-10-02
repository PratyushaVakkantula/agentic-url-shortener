package com.agentic.shortener.domain;

/** The link exists but is expired or deactivated (HTTP 410 Gone). */
public class LinkUnavailableException extends ShortenerException {

    private final LinkStatus status;

    public LinkUnavailableException(String code, LinkStatus status) {
        super("LINK_" + status.name(), "The short link '" + code + "' is " + status.name().toLowerCase() + ".");
        this.status = status;
    }

    public LinkStatus status() {
        return status;
    }
}
