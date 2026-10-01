package com.agentic;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * Modular monolith with three top-level modules (see docs/adr/0001):
 * <ul>
 *   <li>{@code com.agentic.shortener}     – URL shortening, redirects, analytics</li>
 *   <li>{@code com.agentic.orchestration} – agentic SDLC workflow engine</li>
 *   <li>{@code com.agentic.platform}      – cross-cutting: security, web, config</li>
 * </ul>
 * Module boundaries are enforced by ArchUnit tests.
 */
@SpringBootApplication
@ConfigurationPropertiesScan
public class AgenticUrlShortenerApplication {

    public static void main(String[] args) {
        SpringApplication.run(AgenticUrlShortenerApplication.class, args);
    }
}
