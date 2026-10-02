package com.agentic.orchestration.engine;

import com.agentic.orchestration.event.InMemoryRunEventStore;
import com.agentic.orchestration.event.RunEventStore;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OrchestrationConfig {

    /** Replaced by a durable, database-backed store in the next step. */
    @Bean
    RunEventStore runEventStore() {
        return new InMemoryRunEventStore();
    }
}
