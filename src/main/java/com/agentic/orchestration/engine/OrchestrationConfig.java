package com.agentic.orchestration.engine;

import com.agentic.orchestration.event.JdbcRunEventStore;
import com.agentic.orchestration.event.RunEventCodec;
import com.agentic.orchestration.event.RunEventStore;
import com.agentic.orchestration.governance.Policy;
import com.agentic.orchestration.governance.PolicyEngine;
import java.util.List;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

@Configuration
public class OrchestrationConfig {

    @Bean
    RunEventStore runEventStore(JdbcClient jdbc, PlatformTransactionManager txManager, JsonMapper mapper) {
        return new JdbcRunEventStore(jdbc, new TransactionTemplate(txManager), new RunEventCodec(mapper));
    }

    @Bean
    PolicyEngine policyEngine(List<Policy> policies) {
        return new PolicyEngine(policies);
    }

    /** Resume interrupted runs once the app (and Flyway) is fully up. */
    @Bean
    RecoveryOnStartup recoveryOnStartup(WorkflowEngine engine) {
        return new RecoveryOnStartup(engine);
    }

    static final class RecoveryOnStartup {

        private final WorkflowEngine engine;

        RecoveryOnStartup(WorkflowEngine engine) {
            this.engine = engine;
        }

        @EventListener(ApplicationReadyEvent.class)
        void resume() {
            engine.resumeUnfinishedRuns();
        }
    }
}
