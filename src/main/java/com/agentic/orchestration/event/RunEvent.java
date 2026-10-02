package com.agentic.orchestration.event;

import com.agentic.orchestration.governance.PolicyCategory;
import com.agentic.orchestration.governance.PolicyOutcome;
import com.agentic.orchestration.model.ApprovalStatus;
import com.agentic.orchestration.model.Artifact;
import com.agentic.orchestration.model.ArtifactRef;
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

    record PolicyEvaluated(String runId, long seq, Instant at, String stageId, int attempt, String policy,
                           PolicyCategory category, PolicyOutcome outcome, String reason) implements RunEvent {
    }

    /** A non-terminal failure: a retry or fallback follows. Terminal failures are {@link StageFailed}. */
    record AttemptFailed(String runId, long seq, Instant at, String stageId, int attempt,
                         FailureKind kind, String reason) implements RunEvent {
    }

    record RetryScheduled(String runId, long seq, Instant at, String stageId, int nextAttempt,
                          long delayMillis) implements RunEvent {
    }

    record FallbackActivated(String runId, long seq, Instant at, String stageId, String fallbackAgent,
                             String reason) implements RunEvent {
    }

    record ApprovalRequested(String runId, long seq, Instant at, String approvalId, String stageId, int attempt,
                             ArtifactRef artifact, List<String> reasons, Instant expiresAt) implements RunEvent {
    }

    record ApprovalDecided(String runId, long seq, Instant at, String approvalId, String stageId,
                           ApprovalStatus decision, String actor, String comment) implements RunEvent {
    }

    /** Safe-stop (OR-8): no new work starts; in-flight work finishes; the run ends STOPPED. */
    record StopRequested(String runId, long seq, Instant at, String actor, String reason) implements RunEvent {
    }

    /** Rollback plan (OR-7): succeeded stages with compensations, in the order they will be undone. */
    record RollbackStarted(String runId, long seq, Instant at, List<String> stages) implements RunEvent {
    }

    record StageCompensated(String runId, long seq, Instant at, String stageId, boolean succeeded,
                            String detail) implements RunEvent {
    }

    /**
     * A human replaced a stage's output (e.g. corrected the requirements agent's assumptions).
     * Triggers re-planning: consumers of the old version become stale.
     */
    record ArtifactRevised(String runId, long seq, Instant at, Artifact artifact, String actor,
                           String reason) implements RunEvent {
    }

    /**
     * Re-planning (OR-12): the stage's output was derived from inputs that have since changed, so
     * it goes back to PENDING and runs again as a fresh generation (new retry budget).
     */
    record StageInvalidated(String runId, long seq, Instant at, String stageId, List<ArtifactRef> staleInputs,
                            String reason) implements RunEvent {
    }

    record StageSucceeded(String runId, long seq, Instant at, String stageId, int attempt,
                          long durationMillis) implements RunEvent {
    }

    record StageFailed(String runId, long seq, Instant at, String stageId, int attempt,
                       FailureKind kind, String reason) implements RunEvent {
    }

    record StageSkipped(String runId, long seq, Instant at, String stageId, String reason) implements RunEvent {
    }

    /**
     * The process restarted while the run was in flight. Stages that were RUNNING lost their
     * worker and go back to PENDING; they are re-dispatched as a new attempt. Safe because agents
     * only produce proposals (no external side effects), so re-execution is idempotent.
     */
    record RunResumed(String runId, long seq, Instant at, List<String> interruptedStages) implements RunEvent {
    }

    record RunCompleted(String runId, long seq, Instant at, RunStatus status, String reason) implements RunEvent {
    }
}
