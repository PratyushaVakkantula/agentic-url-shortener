package com.agentic.orchestration.state;

import com.agentic.orchestration.event.FailureKind;
import com.agentic.orchestration.model.Approval;
import com.agentic.orchestration.model.Artifact;
import com.agentic.orchestration.model.Decision;
import com.agentic.orchestration.model.Requirement;
import com.agentic.orchestration.model.RunStatus;
import com.agentic.orchestration.model.StageStatus;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** Immutable snapshot of a run, safe to hand to other threads and to serialise. */
public record RunView(
        String runId,
        String workflow,
        int workflowVersion,
        Requirement requirement,
        String initiator,
        RunStatus status,
        String statusReason,
        Instant startedAt,
        Instant finishedAt,
        long eventCount,
        String stopRequestedBy,
        String stopReason,
        List<StageView> stages,
        List<Approval> approvals,
        List<Artifact> artifacts,
        List<Decision> decisions) {

    public StageView stage(String id) {
        return stages.stream().filter(s -> s.id().equals(id)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("No stage " + id));
    }

    public Optional<Artifact> latestArtifact(String stageId) {
        return artifacts.stream().filter(a -> a.stageId().equals(stageId)).reduce((first, second) -> second);
    }

    public record StageView(String id, StageStatus status, String agent, int attempts, boolean fallbackActive,
                            int artifactVersion, FailureKind lastFailureKind, String lastFailure,
                            Instant startedAt, Instant finishedAt, List<RunState.GateRecord> gates,
                            List<RunState.PolicyRecord> policies, String compensation) {
    }
}
