package com.agentic.shortener.service;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.net.URI;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * Shortener settings ({@code app.shortener.*}), validated at startup.
 *
 * @param baseUrl          public origin used to build short URLs, e.g. https://sho.rt
 * @param codeLength       generated code length; 7 gives 62^7 ≈ 3.5 trillion codes
 * @param maxCodeAttempts  retry budget for code collisions before failing loudly
 * @param maxExpiry        furthest allowed expiry from now (A-4)
 */
@Validated
@ConfigurationProperties("app.shortener")
public record ShortenerProperties(
        @NotNull @DefaultValue("http://localhost:8080") URI baseUrl,
        @Min(6) @Max(12) @DefaultValue("7") int codeLength,
        @Min(1) @Max(10) @DefaultValue("5") int maxCodeAttempts,
        @NotNull @DefaultValue("365d") Duration maxExpiry) {
}
