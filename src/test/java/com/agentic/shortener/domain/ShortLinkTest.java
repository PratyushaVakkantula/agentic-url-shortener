package com.agentic.shortener.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class ShortLinkTest {

    private static final Instant T0 = Instant.parse("2026-01-01T00:00:00Z");

    @Test
    void linkWithoutExpiryStaysActive() {
        ShortLink link = ShortLink.create("abc1234", "https://example.com", false, T0, null);
        assertThat(link.statusAt(T0.plus(Duration.ofDays(10_000)))).isEqualTo(LinkStatus.ACTIVE);
    }

    @Test
    void expiresExactlyAtExpiresAt() {
        Instant expiry = T0.plus(Duration.ofHours(1));
        ShortLink link = ShortLink.create("abc1234", "https://example.com", false, T0, expiry);

        assertThat(link.statusAt(expiry.minusMillis(1))).isEqualTo(LinkStatus.ACTIVE);
        assertThat(link.statusAt(expiry)).isEqualTo(LinkStatus.EXPIRED);
    }

    @Test
    void deactivationWinsOverExpiryAndIsIdempotent() {
        ShortLink link = ShortLink.create("abc1234", "https://example.com", false, T0, T0.plusSeconds(60));
        link.deactivate(T0.plusSeconds(10));
        link.deactivate(T0.plusSeconds(20));

        assertThat(link.getDeactivatedAt()).isEqualTo(T0.plusSeconds(10));
        assertThat(link.statusAt(T0.plusSeconds(120))).isEqualTo(LinkStatus.DEACTIVATED);
    }

    @Test
    void rejectsExpiryNotAfterCreation() {
        assertThatThrownBy(() -> ShortLink.create("abc1234", "https://example.com", false, T0, T0))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
