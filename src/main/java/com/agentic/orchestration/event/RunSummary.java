package com.agentic.orchestration.event;

import com.agentic.orchestration.model.RunStatus;
import java.time.Instant;

/** Lightweight row for listing runs without replaying their event logs. */
public record RunSummary(String runId, String workflow, int workflowVersion, String title, String initiator,
                         RunStatus status, Instant startedAt, Instant finishedAt, long eventCount) {
}
