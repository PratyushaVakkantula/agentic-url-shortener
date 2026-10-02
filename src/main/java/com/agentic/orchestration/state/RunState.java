package com.agentic.orchestration.state;

import com.agentic.orchestration.event.FailureKind;
import com.agentic.orchestration.event.RunEvent;
import com.agentic.orchestration.event.RunEvent.ArtifactProduced;
import com.agentic.orchestration.event.RunEvent.DecisionRecorded;
import com.agentic.orchestration.event.RunEvent.GateEvaluated;
import com.agentic.orchestration.event.RunEvent.RunCompleted;
import com.agentic.orchestration.event.RunEvent.RunResumed;
import com.agentic.orchestration.event.RunEvent.RunStarted;
import com.agentic.orchestration.event.RunEvent.StageFailed;
import com.agentic.orchestration.event.RunEvent.StageSkipped;
import com.agentic.orchestration.event.RunEvent.StageStarted;
import com.agentic.orchestration.event.RunEvent.StageSucceeded;
import com.agentic.orchestration.model.Artifact;
import com.agentic.orchestration.model.Decision;
import com.agentic.orchestration.model.Requirement;
import com.agentic.orchestration.model.RunStatus;
import com.agentic.orchestration.model.StageStatus;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Run state as a left fold over its events (event sourcing, OR-15).
 *
 * <p>{@link #apply} is the only mutator and is a pure function of (state, event). Replaying the
 * same events always yields the same state; tests assert that the live state equals a replay.
 * The {@code switch} over the sealed event type is exhaustive, so adding an event type is a
 * compile error until it is handled here.
 *
 * <p>Thread-safety: written by one coordinator thread; read concurrently via {@link #view()}.
 */
public final class RunState {

    private String runId;
    private String workflow;
    private int workflowVersion;
    private Requirement requirement;
    private String initiator;
    private RunStatus status;
    private String statusReason;
    private Instant startedAt;
    private Instant finishedAt;
    private long lastSeq;
    private final Map<String, StageState> stages = new LinkedHashMap<>();
    private final Map<String, Artifact> latestArtifacts = new LinkedHashMap<>();
    private final List<Artifact> artifactHistory = new ArrayList<>();
    private final List<Decision> decisions = new ArrayList<>();

    public static RunState replay(List<RunEvent> events) {
        RunState state = new RunState();
        events.forEach(state::apply);
        return state;
    }

    public synchronized void apply(RunEvent event) {
        if (event.seq() != lastSeq + 1) {
            throw new IllegalStateException("Out-of-order event: expected seq " + (lastSeq + 1) + ", got " + event.seq());
        }
        lastSeq = event.seq();
        switch (event) {
            case RunStarted e -> {
                runId = e.runId();
                workflow = e.workflow();
                workflowVersion = e.workflowVersion();
                requirement = e.requirement();
                initiator = e.initiator();
                status = RunStatus.RUNNING;
                startedAt = e.at();
                e.stageIds().forEach(id -> stages.put(id, new StageState()));
            }
            case GateEvaluated e -> stage(e.stageId()).gates.add(
                    new GateRecord(e.kind().name(), e.gate(), e.passed(), e.reason()));
            case StageStarted e -> {
                StageState s = stage(e.stageId());
                s.status = StageStatus.RUNNING;
                s.attempts = e.attempt();
                s.startedAt = e.at();
                s.finishedAt = null;
            }
            case ArtifactProduced e -> {
                latestArtifacts.put(e.artifact().stageId(), e.artifact());
                artifactHistory.add(e.artifact());
                stage(e.artifact().stageId()).artifactVersion = e.artifact().version();
            }
            case DecisionRecorded e -> decisions.add(e.decision());
            case StageSucceeded e -> {
                StageState s = stage(e.stageId());
                s.status = StageStatus.SUCCEEDED;
                s.finishedAt = e.at();
                s.lastFailure = null;
                s.lastFailureKind = null;
            }
            case StageFailed e -> {
                StageState s = stage(e.stageId());
                s.status = StageStatus.FAILED;
                s.finishedAt = e.at();
                s.lastFailure = e.reason();
                s.lastFailureKind = e.kind();
            }
            case StageSkipped e -> {
                StageState s = stage(e.stageId());
                s.status = StageStatus.SKIPPED;
                s.finishedAt = e.at();
                s.lastFailure = e.reason();
            }
            case RunResumed e -> e.interruptedStages().forEach(id -> {
                StageState s = stage(id);
                s.status = StageStatus.PENDING;
                s.lastFailure = "interrupted by restart during attempt " + s.attempts;
            });
            case RunCompleted e -> {
                status = e.status();
                statusReason = e.reason();
                finishedAt = e.at();
            }
        }
    }

    private StageState stage(String id) {
        StageState s = stages.get(id);
        if (s == null) {
            throw new IllegalStateException("Event for unknown stage '" + id + "'");
        }
        return s;
    }

    // ---- queries used by the coordinator (same thread as apply) ----

    public synchronized StageStatus stageStatus(String id) {
        return stage(id).status;
    }

    public synchronized int attempts(String id) {
        return stage(id).attempts;
    }

    public synchronized Artifact latestArtifact(String stageId) {
        return latestArtifacts.get(stageId);
    }

    public synchronized Map<String, Artifact> latestArtifacts() {
        return Map.copyOf(latestArtifacts);
    }

    public synchronized Requirement requirement() {
        return requirement;
    }

    public synchronized RunStatus status() {
        return status;
    }

    public synchronized long lastSeq() {
        return lastSeq;
    }

    public synchronized boolean anyStage(StageStatus status) {
        return stages.values().stream().anyMatch(s -> s.status == status);
    }

    public synchronized List<String> stagesWithStatus(StageStatus status) {
        return stages.entrySet().stream().filter(e -> e.getValue().status == status).map(Map.Entry::getKey).toList();
    }

    public synchronized String workflow() {
        return workflow;
    }

    public synchronized int workflowVersion() {
        return workflowVersion;
    }

    public synchronized boolean allStages(StageStatus status) {
        return stages.values().stream().allMatch(s -> s.status == status);
    }

    /** Immutable snapshot for readers on other threads (API, tests). */
    public synchronized RunView view() {
        List<RunView.StageView> stageViews = new ArrayList<>();
        stages.forEach((id, s) -> stageViews.add(new RunView.StageView(id, s.status, s.attempts, s.artifactVersion,
                s.lastFailureKind, s.lastFailure, s.startedAt, s.finishedAt, List.copyOf(s.gates))));
        return new RunView(runId, workflow, workflowVersion, requirement, initiator, status, statusReason,
                startedAt, finishedAt, lastSeq, stageViews, List.copyOf(artifactHistory), List.copyOf(decisions));
    }

    private static final class StageState {
        StageStatus status = StageStatus.PENDING;
        int attempts;
        int artifactVersion;
        FailureKind lastFailureKind;
        String lastFailure;
        Instant startedAt;
        Instant finishedAt;
        final List<GateRecord> gates = new ArrayList<>();
    }

    public record GateRecord(String kind, String gate, boolean passed, String reason) {
    }
}
