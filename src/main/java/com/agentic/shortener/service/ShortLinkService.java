package com.agentic.shortener.service;

import com.agentic.shortener.domain.AliasConflictException;
import com.agentic.shortener.domain.CodeSpaceExhaustedException;
import com.agentic.shortener.domain.InvalidLinkRequestException;
import com.agentic.shortener.domain.ShortLink;
import com.agentic.shortener.repository.ShortLinkRepository;
import java.time.Clock;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

/**
 * Creates short links.
 *
 * <p><b>Why {@code create} is not {@code @Transactional}:</b> each insert attempt runs in its
 * own repository transaction. A unique-constraint violation marks a surrounding transaction
 * rollback-only, which would make retrying inside one transaction impossible. With one
 * statement per attempt there is nothing to roll back on a collision.
 */
@Service
public class ShortLinkService {

    private static final Logger log = LoggerFactory.getLogger(ShortLinkService.class);

    private final ShortLinkRepository links;
    private final ShortCodeGenerator codeGenerator;
    private final UrlSafetyValidator urlValidator;
    private final AliasPolicy aliasPolicy;
    private final ShortenerProperties properties;
    private final Clock clock;

    public ShortLinkService(ShortLinkRepository links, ShortCodeGenerator codeGenerator,
                            UrlSafetyValidator urlValidator, AliasPolicy aliasPolicy,
                            ShortenerProperties properties, Clock clock) {
        this.links = links;
        this.codeGenerator = codeGenerator;
        this.urlValidator = urlValidator;
        this.aliasPolicy = aliasPolicy;
        this.properties = properties;
        this.clock = clock;
    }

    public ShortLink create(CreateLinkCommand command) {
        Instant now = clock.instant();
        String url = urlValidator.validate(command.url());
        validateExpiry(command.expiresAt(), now);

        if (command.customAlias() != null) {
            return createWithAlias(url, command.customAlias(), command.expiresAt(), now);
        }
        return createWithGeneratedCode(url, command.expiresAt(), now);
    }

    private ShortLink createWithAlias(String url, String alias, Instant expiresAt, Instant now) {
        aliasPolicy.validate(alias);
        // The pre-check gives a clean 409 in the common case; the constraint catch below
        // covers the race where two requests claim the same alias at the same moment.
        if (links.existsByCode(alias)) {
            throw new AliasConflictException(alias);
        }
        try {
            return links.saveAndFlush(ShortLink.create(alias, url, true, now, expiresAt));
        } catch (DataIntegrityViolationException e) {
            throw new AliasConflictException(alias);
        }
    }

    private ShortLink createWithGeneratedCode(String url, Instant expiresAt, Instant now) {
        int maxAttempts = properties.maxCodeAttempts();
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            String code = codeGenerator.next();
            if (links.existsByCode(code)) {
                log.warn("Short code collision on attempt {}/{} (pre-check)", attempt, maxAttempts);
                continue;
            }
            try {
                return links.saveAndFlush(ShortLink.create(code, url, false, now, expiresAt));
            } catch (DataIntegrityViolationException e) {
                log.warn("Short code collision on attempt {}/{} (concurrent insert)", attempt, maxAttempts);
            }
        }
        throw new CodeSpaceExhaustedException(maxAttempts);
    }

    private void validateExpiry(Instant expiresAt, Instant now) {
        if (expiresAt == null) {
            return;
        }
        if (!expiresAt.isAfter(now)) {
            throw new InvalidLinkRequestException("INVALID_EXPIRY", "expiresAt", "expiresAt must be in the future.");
        }
        if (expiresAt.isAfter(now.plus(properties.maxExpiry()))) {
            throw new InvalidLinkRequestException("INVALID_EXPIRY", "expiresAt",
                    "expiresAt must be at most " + properties.maxExpiry().toDays() + " days from now.");
        }
    }
}
