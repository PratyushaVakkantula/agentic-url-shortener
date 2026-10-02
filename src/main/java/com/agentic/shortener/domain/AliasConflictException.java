package com.agentic.shortener.domain;

public class AliasConflictException extends ShortenerException {

    public AliasConflictException(String alias) {
        super("ALIAS_TAKEN", "The alias '" + alias + "' is already in use.");
    }
}
