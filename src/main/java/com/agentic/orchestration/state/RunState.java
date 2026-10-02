package com.agentic.orchestration.state;

import com.agentic.orchestration.event.FailureKind;
import com.agentic.orchestration.event.RunEvent;
import com.agentic.orchestration.event.RunEvent.ApprovalDecided;
import com.agentic.orchestration.event.RunEvent.ApprovalRequested;
import com.agentic.orchestration.event.RunEvent.ArtifactProduced;
import com.agentic.orchestration.event.RunEvent.ArtifactRevised;
import com.agentic.orchestration.event.RunEvent.AttemptFailed;
import com.agentic.orchestration.event.RunEvent.DecisionRecorded;
import com.agentic.orchestration.event.RunEvent.FallbackActivated;
import com.agentic.orchestration.event.RunEvent.GateEvaluated;
import com.agentic.orchestration.event.RunEvent.PolicyEvaluated;
import com.agentic.orchestration.event.RunEvent.RetryScheduled;
import com.agentic.orchestration.event.RunEvent.RollbackStarted;
import com.agentic.orchestration.event.RunEvent.RunCompleted;
import com.agentic.orchestration.event.RunEvent.RunResumed;
import com.agentic.orchestration.event.RunEvent.RunStarted;
import com.agentic.orchestration.event.RunEvent.StageCompensated;
import com.agentic.orchestration.event.RunEvent.StageFailed;
import com.agentic.orchestration.event.RunEvent.StageInvalidated;
import com.agentic.orchestration.event.RunEvent.StageSkipped;
import com.agentic.orchestration.event.RunEvent.StageStarted;
import com.agentic.orchestration.event.RunEvent.StageSucceeded;
import com.agentic.orchestration.event.RunEvent.StopRequested;
import com.agentic.orchestration.model.Approval;
import com.agentic.orchestration.model.ApprovalStatus;
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
import java.util.Optional;

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
    private String stopRequestedBy;
    private String stopReason;
    private List<String> rollbackPlan;
    private final Map<String, StageState> stages = new LinkedHashMap<>();
    private final Map<String, Artifact> latestArtifacts = new LinkedHashMap<>();
    private final List<Artifact> artifactHistory = new ArrayList<>();
    private final List<Decision> decisions = new ArrayList<>();
    private final Map<String, Approval> approvals = new LinkedHashMap<>();
    private final List<String> completionOrder = new ArrayList<>();

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
                s.agent = e.agent();
                s.startedAt = e.at();
                s.finishedAt = null;
            }
            case ArtifactProduced e -> {
                latestArtifacts.put(e.artifact().stageId(), e.artifact());
                artifactHistory.add(e.artifact());
                stage(e.artifact().stageId()).artifactVersion = e.artifact().version();
            }
            case ArtifactRevised e -> {
                latestArtifacts.put(e.artifact().stageId(), e.artifact());
                artifactHistory.add(e.artifact());
                stage(e.artifact().stageId()).artifactVersion = e.artifact().version();
            }
            case StageInvalidated e -> {
                StageState s = stage(e.stageId());
                s.status = StageStatus.PENDING;
                s.generationBase = s.attempts;
                s.fallbackActive = false;
                s.invalidations++;
                s.lastFailure = e.reason();
                s.lastFailureKind = null;
                completionOrder.remove(e.stageId());
            }
            case DecisionRecorded e -> decisions.add(e.decision());
            case PolicyEvaluated e -> stage(e.stageId()).policies.add(
                    new PolicyRecord(e.attempt(), e.policy(), e.category().name(), e.outcome().name(), e.reason()));
            case AttemptFailed e -> {
                StageState s = stage(e.stageId());
                s.lastFailureKind = e.kind();
                s.lastFailure = e.reason();
            }
            case RetryScheduled e -> stage(e.stageId()).status = StageStatus.WAITING_RETRY;
            case FallbackActivated e -> stage(e.stageId()).fallbackActive = true;
            case ApprovalRequested e -> {
                stage(e.stageId()).status = StageStatus.AWAITING_APPROVAL;
                approvals.put(e.approvalId(), new Approval(e.approvalId(), e.stageId(), e.attempt(), e.artifact(),
                        e.reasons(), e.at(), e.expiresAt(), ApprovalStatus.PENDING, null, null, null));
            }
            case ApprovalDecided e -> approvals.computeIfPresent(e.approvalId(),
                    (id, a) -> a.decide(e.decision(), e.actor(), e.comment(), e.at()));
            case StageSucceeded e -> {
                StageState s = stage(e.stageId());
                s.status = StageStatus.SUCCEEDED;
                s.finishedAt = e.at();
                s.lastFailure = null;
                s.lastFailureKind = null;
                completionOrder.remove(e.stageId()); // a re-planned stage moves to its latest completion
                completionOrder.add(e.stageId());
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
            case StopRequested e -> {
                stopRequestedBy = e.actor();
                stopReason = e.reason();
            }
            case RollbackStarted e -> rollbackPlan = List.copyOf(e.stages());
            case StageCompensated e -> {
                StageState s = stage(e.stageId());
                s.status = e.succeeded() ? StageStatus.ROLLED_BACK : StageStatus.ROLLBACK_FAILED;
                s.compensation = e.detail();
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

    // ---- queries used by the coordinator ----

    public synchronized StageStatus stageStatus(String id) {
        return stage(id).status;
    }

    public synchronized int attempts(String id) {
        return stage(id).attempts;
    }

    /** Attempts made in the current generation (resets when the stage is invalidated by re-planning). */
    public synchronized int attemptsInGeneration(String id, int attempt) {
        return attempt - stage(id).generationBase;
    }

    public synchronized boolean fallbackActive(String id) {
        return stage(id).fallbackActive;
    }

    public synchronized Artifact latestArtifact(String stageId) {
        return latestArtifacts.get(stageId);
    }

    public synchronized Requirement requirement() {
        return requirement;
    }

    public synchronized String initiator() {
        return initiator;
    }

    public synchronized String workflow() {
        return workflow;
    }

    public synchronized RunStatus status() {
        return status;
    }

    public synchronized long lastSeq() {
        return lastSeq;
    }

    public synchronized boolean stopRequested() {
        return stopRequestedBy != null;
    }

    public synchronized String stopReason() {
        return stopReason;
    }

    public synchronized Optional<List<String>> rollbackPlan() {
        return Optional.ofNullable(rollbackPlan);
    }

    public synchronized List<String> completionOrder() {
        return List.copyOf(completionOrder);
    }

    public synchronized Optional<Approval> approval(String approvalId) {
        return Optional.ofNullable(approvals.get(approvalId));
    }

    public synchronized List<Approval> pendingApprovals() {
        return approvals.values().stream().filter(a -> a.status() == ApprovalStatus.PENDING).toList();
    }

    public synchronized List<String> stagesWithStatus(StageStatus status) {
        return stages.entrySet().stream().filter(e -> e.getValue().status == status).map(Map.Entry::getKey).toList();
    }

    public synchronized boolean anyStage(StageStatus status) {
        return stages.values().stream().anyMatch(s -> s.status == status);
    }

    public synchronized boolean anyStageActive() {
        return stages.values().stream().anyMatch(s -> s.status.isActive());
    }

    public synchronized boolean allStages(StageStatus status) {
        return stages.values().stream().allMatch(s -> s.status == status);
    }

    /** Immutable snapshot for readers on other threads (API, tests). */
    public synchronized RunView view() {
        List<RunView.StageView> stageViews = new ArrayList<>();
        stages.forEach((id, s) -> stageViews.add(new RunView.StageView(id, s.status, s.agent, s.attempts,
                s.fallbackActive, s.invalidations, s.artifactVersion, s.lastFailureKind, s.lastFailure, s.startedAt, s.finishedAt,
                List.copyOf(s.gates), List.copyOf(s.policies), s.compensation)));
        return new RunView(runId, workflow, workflowVersion, requirement, initiator, status, statusReason,
                startedAt, finishedAt, lastSeq, stopRequestedBy, stopReason, stageViews,
                List.copyOf(approvals.values()), List.copyOf(artifactHistory), List.copyOf(decisions));
    }

    private static final class StageState {
        StageStatus status = StageStatus.PENDING;
        String agent;
        int attempts;
        boolean fallbackActive;
        int generationBase;
        int invalidations;
        int artifactVersion;
        FailureKind lastFailureKind;
        String lastFailure;
        Instant startedAt;
        Instant finishedAt;
        String compensation;
        final List<GateRecord> gates = new ArrayList<>();
        final List<PolicyRecord> policies = new ArrayList<>();
    }

    public record GateRecord(String kind, String gate, boolean passed, String reason) {
    }

    public record PolicyRecord(int attempt, String policy, String category, String outcome, String reason) {
    }
}
