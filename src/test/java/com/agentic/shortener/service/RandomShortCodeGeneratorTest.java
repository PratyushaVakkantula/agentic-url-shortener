package com.agentic.shortener.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.time.Duration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class RandomShortCodeGeneratorTest {

    private final RandomShortCodeGenerator generator = new RandomShortCodeGenerator(
            new ShortenerProperties(URI.create("http://localhost"), 7, 5, Duration.ofDays(365)));

    @Test
    void producesCodesOfConfiguredLengthFromBase62Alphabet() {
        for (int i = 0; i < 1_000; i++) {
            assertThat(generator.next()).hasSize(7).matches("[0-9A-Za-z]{7}");
        }
    }

    @Test
    void codesDoNotRepeatInPractice() {
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < 100_000; i++) {
            seen.add(generator.next());
        }
        // P(any collision in 1e5 draws from 62^7) ≈ 0.14%; a real repeat would indicate a broken RNG.
        assertThat(seen).hasSizeGreaterThanOrEqualTo(99_999);
    }

    @Test
    void usesTheWholeAlphabetRoughlyUniformly() {
        Map<Character, Integer> counts = new HashMap<>();
        int draws = 20_000;
        for (int i = 0; i < draws; i++) {
            for (char c : generator.next().toCharArray()) {
                counts.merge(c, 1, Integer::sum);
            }
        }
        double expected = draws * 7.0 / 62;
        assertThat(counts).hasSize(62);
        // Generous ±20% band: catches modulo bias or a truncated alphabet, never flaky.
        counts.values().forEach(n -> assertThat((double) n).isBetween(expected * 0.8, expected * 1.2));
    }
}
