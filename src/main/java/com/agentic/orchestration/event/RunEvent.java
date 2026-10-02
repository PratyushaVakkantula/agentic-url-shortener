package com.agentic.orchestration.event;

import com.agentic.orchestration.model.Artifact;
import com.agentic.orchestration.model.Decision;
import com.agentic.orchestration.model.Requirement;
import com.agentic.orchestration.model.RunStatus;
import java.time.Instant;
import java.util.List;

/**
 * Everything that happens in a run, as immutable facts (OR-10, OR-15). The ordered event list
 * is the <b>source of truth</b>: run state is derived by folding events
 * ({@link com.agentic.orchestration.state.RunState#replay}), so the audit trail can never
 * disagree with the state it explains.
 *
 * <p>{@code seq} is a per-run, gap-free sequence assigned by the run's single writer.
 */
public sealed interface RunEvent {

    String runId();

    long seq();

    Instant at();

    record RunStarted(String runId, long seq, Instant at, String workflow, int workflowVersion,
                      Requirement requirement, String initiator, List<String> stageIds) implements RunEvent {
    }

    record GateEvaluated(String runId, long seq, Instant at, String stageId, GateKind kind,
                         String gate, boolean passed, String reason) implements RunEvent {
    }

    record StageStarted(String runId, long seq, Instant at, String stageId, int attempt,
                        String agent) implements RunEvent {
    }

    record ArtifactProduced(String runId, long seq, Instant at, Artifact artifact) implements RunEvent {
    }

    record DecisionRecorded(String runId, long seq, Instant at, Decision decision) implements RunEvent {
    }

    record StageSucceeded(String runId, long seq, Instant at, String stageId, int attempt,
                          long durationMillis) implements RunEvent {
    }

    record StageFailed(String runId, long seq, Instant at, String stageId, int attempt,
                       FailureKind kind, String reason) implements RunEvent {
    }

    record StageSkipped(String runId, long seq, Instant at, String stageId, String reason) implements RunEvent {
    }

    record RunCompleted(String runId, long seq, Instant at, RunStatus status, String reason) implements RunEvent {
    }
}
