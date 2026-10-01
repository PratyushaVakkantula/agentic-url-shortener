package com.agentic;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest
@ActiveProfiles("test")
class ApplicationContextTest {

    @Test
    void contextLoadsWithMigrationsAppliedAndMappingsValidated() {
        // Startup fails if Flyway migrations break or JPA mappings drift from the schema.
    }
}
