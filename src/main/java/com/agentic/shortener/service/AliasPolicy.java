package com.agentic.shortener.service;

import com.agentic.shortener.domain.InvalidLinkRequestException;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * Rules for user-chosen aliases (A-5). Aliases share the root path with system routes
 * ({@code GET /{code}}), so reserved words are rejected to stop an alias from shadowing
 * or impersonating them, case-insensitively, because "/Admin" looks official too.
 */
@Component
public class AliasPolicy {

    private static final Pattern ALLOWED = Pattern.compile("[A-Za-z0-9_-]{3,30}");

    static final Set<String> RESERVED = Set.of(
            "api", "actuator", "admin", "assets", "docs", "error", "favicon", "h2-console",
            "health", "login", "logout", "metrics", "static", "swagger-ui", "v3", "workflows");

    public void validate(String alias) {
        if (alias == null || !ALLOWED.matcher(alias).matches()) {
            throw new InvalidLinkRequestException("INVALID_ALIAS", "customAlias",
                    "Alias must be 3-30 characters of letters, digits, '-' or '_'.");
        }
        if (RESERVED.contains(alias.toLowerCase(Locale.ROOT))) {
            throw new InvalidLinkRequestException("RESERVED_ALIAS", "customAlias",
                    "Alias '" + alias + "' is reserved.");
        }
    }
}
