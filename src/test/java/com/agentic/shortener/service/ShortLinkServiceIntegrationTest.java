package com.agentic.shortener.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.agentic.shortener.domain.AliasConflictException;
import com.agentic.shortener.domain.CodeSpaceExhaustedException;
import com.agentic.shortener.domain.InvalidLinkRequestException;
import com.agentic.shortener.domain.ShortLink;
import com.agentic.shortener.repository.ShortLinkRepository;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Runs against the real schema (Flyway migrations) so unique-constraint behaviour is the
 * database's, not a mock's.
 *
 * <p>Test-managed transactions are disabled: the service relies on each insert attempt having
 * its own transaction (see ShortLinkService javadoc), and wrapping the test in one would hide
 * exactly the behaviour under test. Tables are cleaned after each test instead.
 */
@DataJpaTest
@ActiveProfiles("test")
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class ShortLinkServiceIntegrationTest {

    private static final Instant NOW = Instant.parse("2026-06-01T12:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    private static final ShortenerProperties PROPS =
            new ShortenerProperties(URI.create("http://localhost:8080"), 7, 3, Duration.ofDays(365));

    @Autowired
    ShortLinkRepository repository;

    @AfterEach
    void cleanUp() {
        repository.deleteAll();
    }

    private ShortLinkService service(ShortCodeGenerator generator) {
        return new ShortLinkService(repository, generator, new UrlSafetyValidator(), new AliasPolicy(), PROPS, CLOCK);
    }

    private static ShortCodeGenerator scripted(String... codes) {
        Iterator<String> it = List.of(codes).iterator();
        return it::next;
    }

    @Test
    void createsAndPersistsLinkWithGeneratedCode() {
        ShortLink link = service(scripted("Ab3dE9x")).create(new CreateLinkCommand("https://example.com/a", null, null));

        ShortLink stored = repository.findByCode("Ab3dE9x").orElseThrow();
        assertThat(stored.getId()).isEqualTo(link.getId());
        assertThat(stored.getOriginalUrl()).isEqualTo("https://example.com/a");
        assertThat(stored.isCustomAlias()).isFalse();
        assertThat(stored.getCreatedAt()).isEqualTo(NOW);
        assertThat(stored.getClickCount()).isZero();
    }

    @Test
    void retriesOnCodeCollisionAndUsesNextFreeCode() {
        service(scripted("SAME001")).create(new CreateLinkCommand("https://example.com/1", null, null));

        ShortLink second = service(scripted("SAME001", "SAME001", "FRESH02"))
                .create(new CreateLinkCommand("https://example.com/2", null, null));

        assertThat(second.getCode()).isEqualTo("FRESH02");
        assertThat(repository.count()).isEqualTo(2);
    }

    @Test
    void failsLoudlyWhenRetryBudgetIsExhausted() {
        service(scripted("TAKEN01")).create(new CreateLinkCommand("https://example.com/1", null, null));
        ShortCodeGenerator alwaysTaken = () -> "TAKEN01";

        assertThatThrownBy(() -> service(alwaysTaken).create(new CreateLinkCommand("https://example.com/2", null, null)))
                .isInstanceOf(CodeSpaceExhaustedException.class)
                .hasMessageContaining("3 attempts");
        assertThat(repository.count()).isEqualTo(1);
    }

    @Test
    void customAliasIsStoredAsCodeAndSecondClaimConflicts() {
        ShortLinkService service = service(() -> { throw new AssertionError("generator must not be used"); });
        ShortLink link = service.create(new CreateLinkCommand("https://example.com", "launch-2026", null));

        assertThat(link.getCode()).isEqualTo("launch-2026");
        assertThat(link.isCustomAlias()).isTrue();
        assertThatThrownBy(() -> service.create(new CreateLinkCommand("https://other.example", "launch-2026", null)))
                .isInstanceOf(AliasConflictException.class);
    }

    @Test
    void concurrentClaimsOfSameAliasProduceExactlyOneWinner() throws Exception {
        ShortLinkService service = service(() -> "unused0");
        int contenders = 8;
        CountDownLatch start = new CountDownLatch(1);
        Callable<Boolean> claim = () -> {
            start.await();
            try {
                service.create(new CreateLinkCommand("https://example.com", "hot-alias", null));
                return true;
            } catch (AliasConflictException e) {
                return false;
            }
        };

        List<Future<Boolean>> results = new ArrayList<>();
        try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int i = 0; i < contenders; i++) {
                results.add(pool.submit(claim));
            }
            start.countDown();
        }

        long winners = results.stream().filter(f -> f.state() == Future.State.SUCCESS && f.resultNow()).count();
        assertThat(winners).isEqualTo(1);
        assertThat(results).allMatch(f -> f.state() == Future.State.SUCCESS, "no unexpected exceptions");
        assertThat(repository.count()).isEqualTo(1);
    }

    @Test
    void validatesExpiryWindow() {
        ShortLinkService service = service(scripted("EXP0001"));

        assertThatThrownBy(() -> service.create(new CreateLinkCommand("https://example.com", null, NOW)))
                .isInstanceOf(InvalidLinkRequestException.class)
                .hasFieldOrPropertyWithValue("errorCode", "INVALID_EXPIRY");
        assertThatThrownBy(() -> service.create(new CreateLinkCommand("https://example.com", null, NOW.plus(Duration.ofDays(366)))))
                .hasFieldOrPropertyWithValue("errorCode", "INVALID_EXPIRY");

        ShortLink ok = service.create(new CreateLinkCommand("https://example.com", null, NOW.plus(Duration.ofDays(365))));
        assertThat(ok.getExpiresAt()).isEqualTo(NOW.plus(Duration.ofDays(365)));
    }

    @Test
    void rejectsUnsafeUrlBeforeTouchingTheDatabase() {
        assertThatThrownBy(() -> service(scripted("NEVER01")).create(new CreateLinkCommand("http://169.254.169.254/", null, null)))
                .hasFieldOrPropertyWithValue("errorCode", "BLOCKED_HOST");
        assertThat(repository.count()).isZero();
    }
}
