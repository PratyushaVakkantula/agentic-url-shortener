package com.agentic.orchestration.engine;

import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * Engine-wide governance defaults ({@code app.orchestration.*}).
 *
 * @param defaultStageTimeout per-attempt limit for stages that do not set their own (OR-16)
 * @param approvalTtl         how long a human checkpoint stays open before it expires (OR-14)
 */
@Validated
@ConfigurationProperties("app.orchestration")
public record GovernanceSettings(
        @NotNull @DefaultValue("10m") Duration defaultStageTimeout,
        @NotNull @DefaultValue("24h") Duration approvalTtl) {
}
