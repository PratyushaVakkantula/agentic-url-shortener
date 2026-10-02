package com.agentic.support;

import java.time.Instant;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/** Replaces the application Clock with a {@link MutableClock} for time-travel tests. */
@TestConfiguration(proxyBeanMethods = false)
public class TestClockConfig {

    public static final Instant T0 = Instant.parse("2026-03-10T09:00:00Z");

    @Bean
    @Primary
    MutableClock mutableClock() {
        return new MutableClock(T0);
    }
}
