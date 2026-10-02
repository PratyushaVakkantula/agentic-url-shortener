package com.agentic.orchestration.engine;

import com.agentic.orchestration.agent.Agent;
import com.agentic.orchestration.agent.AgentResult;
import com.agentic.orchestration.agent.ContextAccessException;
import com.agentic.orchestration.agent.StageContext;
import com.agentic.orchestration.definition.CompensationContext;
import com.agentic.orchestration.definition.Gate;
import com.agentic.orchestration.definition.GateInput;
import com.agentic.orchestration.definition.GateResult;
import com.agentic.orchestration.definition.RetryPolicy;
import com.agentic.orchestration.definition.StageDefinition;
import com.agentic.orchestration.definition.WorkflowDefinition;
import com.agentic.orchestration.engine.GovernanceException.Violation;
import com.agentic.orchestration.engine.StageOutcome.Completed;
import com.agentic.orchestration.engine.StageOutcome.Errored;
import com.agentic.orchestration.engine.StageOutcome.GateCheck;
import com.agentic.orchestration.event.FailureKind;
import com.agentic.orchestration.event.GateKind;
import com.agentic.orchestration.event.RunEvent;
import com.agentic.orchestration.event.RunEventStore;
import com.agentic.orchestration.governance.PolicyDecision;
import com.agentic.orchestration.governance.PolicyEngine;
import com.agentic.orchestration.governance.PolicyInput;
import com.agentic.orchestration.governance.PolicyOutcome;
import com.agentic.orchestration.model.Approval;
import com.agentic.orchestration.model.ApprovalStatus;
import com.agentic.orchestration.model.Artifact;
import com.agentic.orchestration.model.ArtifactRef;
import com.agentic.orchestration.model.Decision;
import com.agentic.orchestration.model.Requirement;
import com.agentic.orchestration.model.RunStatus;
import com.agentic.orchestration.model.StageStatus;
import com.agentic.orchestration.state.RunState;
import com.agentic.orchestration.state.RunView;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.LongFunction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Drives one workflow run. An <b>actor</b>: a single coordinator thread owns the run's state and
 * processes {@link Signal}s from a mailbox one at a time. Agents execute concurrently on a shared
 * virtual-thread pool; timers (timeouts, retry backoff, approval expiry) and humans (approvals,
 * stop requests) also just post signals. Nothing else ever touches run state.
 *
 * <p>Every change is appended to the {@link RunEventStore} first and then applied to
 * {@link RunState} (write-ahead), so the audit log is always at least as current as state.
 *
 * <h2>Governance flow of one stage attempt</h2>
 * <pre>
 *  entry gates ─fail─▶ FAILED (not retried)
 *      │ pass
 *  agent runs ──error / timeout──┐
 *      │                         │
 *  exit gates ──fail─────────────┤──▶ retry with backoff ─▶ fallback agent ─▶ FAILED
 *      │ pass
 *  policies ──BLOCK──▶ FAILED + safe-stop of the run
 *      │ REQUIRE_APPROVAL, or stage marked high-impact
 *      ├──────────────▶ AWAITING_APPROVAL ─approve─▶ SUCCEEDED / ─reject|expire─▶ FAILED
 *      │ ALLOW
 *  SUCCEEDED
 * </pre>
 * Run level: fail fast (no new stages after a failure), then roll back succeeded stages in
 * reverse completion order. Safe-stop never rolls back: it preserves state for investigation.
 */
final class RunCoordinator {

    private static final Logger log = LoggerFactory.getLogger(RunCoordinator.class);
    private static final String SYSTEM = "system";

    sealed interface Signal permits Schedule, StageOutcome, AttemptTimedOut, RetryDue, ApprovalCommand,
            ApprovalExpired, StopCommand, CompensationFinished, ReviseCommand {
    }

    record Schedule() implements Signal {
    }

    record AttemptTimedOut(String stageId, int attempt) implements Signal {
    }

    record RetryDue(String stageId) implements Signal {
    }

    record ApprovalCommand(String approvalId, boolean approve, String actor, String artifactHash, String comment,
                           CompletableFuture<Approval> reply) implements Signal {
    }

    record ApprovalExpired(String approvalId) implements Signal {
    }

    record StopCommand(String actor, String reason, CompletableFuture<Void> reply) implements Signal {
    }

    record CompensationFinished(String stageId, boolean succeeded, String detail) implements Signal {
    }

    record ReviseCommand(String stageId, JsonNode content, String actor, String reason,
                         CompletableFuture<Artifact> reply) implements Signal {
    }

    private final String runId;
    private final WorkflowDefinition definition;
    private final RunEventStore store;
    private final ExecutorService agentExecutor;
    private final ScheduledExecutorService scheduler;
    private final PolicyEngine policies;
    private final GovernanceSettings settings;
    private final JsonMapper mapper;
    private final ContentHasher hasher;
    private final Clock clock;
    private final RunState state = new RunState();
    private final BlockingQueue<Signal> mailbox = new LinkedBlockingQueue<>();
    private final CompletableFuture<RunView> completion = new CompletableFuture<>();
    private volatile Thread thread;

    // Owned by the coordinator thread only; rebuilt from state on resume.
    private final Map<String, Future<?>> inflight = new HashMap<>();
    private final Map<String, ScheduledFuture<?>> timers = new HashMap<>();
    private boolean compensating;

    RunCoordinator(String runId, WorkflowDefinition definition, RunEventStore store, ExecutorService agentExecutor,
                   ScheduledExecutorService scheduler, PolicyEngine policies, GovernanceSettings settings,
                   JsonMapper mapper, Clock clock) {
        this.runId = runId;
        this.definition = definition;
        this.store = store;
        this.agentExecutor = agentExecutor;
        this.scheduler = scheduler;
        this.policies = policies;
        this.settings = settings;
        this.mapper = mapper;
        this.hasher = new ContentHasher(mapper);
        this.clock = clock;
    }

    // ------------------------------------------------------------------ lifecycle (any thread)

    void start(Requirement requirement, String initiator) {
        emit(seq -> new RunEvent.RunStarted(runId, seq, now(), definition.name(), definition.version(),
                requirement, initiator, definition.topologicalOrder()));
        launch();
    }

    /**
     * Continues a run from its persisted history after a restart (OR-15): rebuild state by replay,
     * record which stages were interrupted, re-arm timers for retries and open approvals.
     */
    void resume(List<RunEvent> history) {
        history.forEach(state::apply);
        List<String> interrupted = state.stagesWithStatus(StageStatus.RUNNING);
        emit(seq -> new RunEvent.RunResumed(runId, seq, now(), interrupted));
        state.stagesWithStatus(StageStatus.WAITING_RETRY).forEach(id -> arm("retry:" + id, Duration.ZERO, new RetryDue(id)));
        state.pendingApprovals().forEach(a -> arm("approval:" + a.approvalId(),
                Duration.between(now(), a.expiresAt()), new ApprovalExpired(a.approvalId())));
        launch();
    }

    /** Stops without writing anything, exactly as if the process died (see ADR-0006). */
    void stop() throws InterruptedException {
        Thread t = thread;
        if (t != null) {
            t.interrupt();
            t.join(Duration.ofSeconds(5));
        }
    }

    void post(Signal signal) {
        mailbox.add(signal);
    }

    boolean isFinished() {
        return completion.isDone();
    }

    RunView view() {
        return state.view();
    }

    CompletableFuture<RunView> completion() {
        return completion;
    }

    private void launch() {
        mailbox.add(new Schedule());
        thread = Thread.ofVirtual().name("run-" + runId).start(this::loop);
    }

    // ------------------------------------------------------------------ coordinator thread

    private void loop() {
        MDC.put("runId", runId);
        try {
            while (!state.status().isTerminal()) {
                handle(mailbox.take());
            }
            completion.complete(state.view());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            completion.completeExceptionally(new IllegalStateException("engine stopped; run " + runId + " resumes on restart"));
        } catch (RuntimeException e) {
            log.error("Coordinator for run {} crashed", runId, e);
            completion.completeExceptionally(e);
        } finally {
            timers.values().forEach(t -> t.cancel(false));
            rejectPendingCommands();
            MDC.remove("runId");
        }
    }

    private void handle(Signal signal) {
        switch (signal) {
            case Schedule s -> schedule();
            case Completed c -> onCompleted(c);
            case Errored e -> onErrored(e);
            case AttemptTimedOut t -> onTimeout(t);
            case RetryDue r -> onRetryDue(r.stageId());
            case ApprovalCommand a -> onApprovalCommand(a);
            case ApprovalExpired x -> onApprovalExpired(x.approvalId());
            case StopCommand s -> onStop(s);
            case CompensationFinished f -> onCompensationFinished(f);
            case ReviseCommand r -> onRevise(r);
        }
    }

    // ---- stage outcomes ----

    private void onCompleted(Completed outcome) {
        String id = outcome.stageId();
        if (!isCurrentAttempt(id, outcome.attempt())) {
            log.info("Discarding late outcome of {} attempt {} (already timed out or superseded)", id, outcome.attempt());
            return;
        }
        endAttempt(id);
        StageDefinition stage = definition.stage(id);

        Artifact previous = state.latestArtifact(id);
        Artifact artifact = new Artifact(id, previous == null ? 1 : previous.version() + 1,
                hasher.hash(outcome.output()), outcome.output(), now(), outcome.inputs());
        emit(seq -> new RunEvent.ArtifactProduced(runId, seq, now(), artifact));
        outcome.decisions().forEach(d -> emit(seq -> new RunEvent.DecisionRecorded(runId, seq, now(), d)));

        // An input was revised while this attempt ran: its output is already outdated. Not a
        // failure (the agent did nothing wrong), so re-run as a new generation instead of retrying.
        List<ArtifactRef> staleInputs = staleInputs(artifact);
        if (!staleInputs.isEmpty()) {
            emit(seq -> new RunEvent.StageInvalidated(runId, seq, now(), id, staleInputs,
                    "inputs changed while the stage was running: " + staleInputs));
            schedule();
            return;
        }
        outcome.exitGates().forEach(g -> emit(seq -> new RunEvent.GateEvaluated(runId, seq, now(), id, GateKind.EXIT,
                g.gate(), g.passed(), g.reason())));
        outcome.policies().forEach(p -> emit(seq -> new RunEvent.PolicyEvaluated(runId, seq, now(), id,
                outcome.attempt(), p.policy(), p.category(), p.outcome(), p.reason())));

        List<String> failedGates = outcome.exitGates().stream().filter(g -> !g.passed())
                .map(g -> g.gate() + ": " + g.reason()).toList();
        if (!failedGates.isEmpty()) {
            failAttempt(id, outcome.attempt(), FailureKind.EXIT_GATE, String.join("; ", failedGates));
        } else if (PolicyEngine.mostSevere(outcome.policies()) == PolicyOutcome.BLOCK) {
            String reason = summarize(outcome.policies(), PolicyOutcome.BLOCK);
            emit(seq -> new RunEvent.StageFailed(runId, seq, now(), id, outcome.attempt(), FailureKind.POLICY_BLOCKED, reason));
            requestStop("policy-engine", "output of stage '" + id + "' blocked: " + reason);
        } else {
            List<String> approvalReasons = new ArrayList<>();
            if (stage.policy().requiresApproval()) {
                approvalReasons.add("high-impact stage: " + stage.policy().approvalReason());
            }
            outcome.policies().stream().filter(p -> p.outcome() == PolicyOutcome.REQUIRE_APPROVAL)
                    .forEach(p -> approvalReasons.add(p.category() + "/" + p.policy() + ": " + p.reason()));
            if (approvalReasons.isEmpty()) {
                emit(seq -> new RunEvent.StageSucceeded(runId, seq, now(), id, outcome.attempt(), outcome.durationMillis()));
            } else {
                requestApproval(id, outcome.attempt(), artifact.ref(), approvalReasons);
            }
        }
        schedule();
    }

    private void onErrored(Errored outcome) {
        if (!isCurrentAttempt(outcome.stageId(), outcome.attempt())) {
            log.info("Discarding late failure of {} attempt {}", outcome.stageId(), outcome.attempt());
            return;
        }
        endAttempt(outcome.stageId());
        failAttempt(outcome.stageId(), outcome.attempt(), outcome.kind(), outcome.reason());
        schedule();
    }

    private void onTimeout(AttemptTimedOut timeout) {
        String id = timeout.stageId();
        if (!isCurrentAttempt(id, timeout.attempt())) {
            return;
        }
        Future<?> worker = inflight.remove(id);
        if (worker != null) {
            worker.cancel(true); // interrupts the agent; its eventual outcome is discarded as stale
        }
        timers.remove("timeout:" + id);
        failAttempt(id, timeout.attempt(), FailureKind.TIMEOUT, "attempt exceeded timeout of " + timeoutOf(definition.stage(id)));
        schedule();
    }

    /** Bounded retry (OR-6) → single fallback attempt → terminal failure. */
    private void failAttempt(String id, int attempt, FailureKind kind, String reason) {
        StageDefinition stage = definition.stage(id);
        RetryPolicy retry = stage.policy().retry();
        Agent fallback = stage.policy().fallback();
        boolean onFallback = state.fallbackActive(id);
        boolean mayContinue = !state.stopRequested() && !state.anyStage(StageStatus.FAILED);

        int attemptInGeneration = state.attemptsInGeneration(id, attempt);
        if (mayContinue && kind.retryable() && !onFallback && attemptInGeneration < retry.maxAttempts()) {
            Duration delay = retry.backoffAfter(attempt);
            emit(seq -> new RunEvent.AttemptFailed(runId, seq, now(), id, attempt, kind, reason));
            emit(seq -> new RunEvent.RetryScheduled(runId, seq, now(), id, attempt + 1, delay.toMillis()));
            arm("retry:" + id, delay, new RetryDue(id));
        } else if (mayContinue && fallback != null && !onFallback && kind != FailureKind.ENTRY_GATE) {
            emit(seq -> new RunEvent.AttemptFailed(runId, seq, now(), id, attempt, kind, reason));
            emit(seq -> new RunEvent.FallbackActivated(runId, seq, now(), id, fallback.name(),
                    "primary agent failed after " + attemptInGeneration + " attempt(s): " + reason));
            dispatch(stage);
        } else {
            emit(seq -> new RunEvent.StageFailed(runId, seq, now(), id, attempt, kind, reason));
        }
    }

    private void onRetryDue(String id) {
        timers.remove("retry:" + id);
        if (state.stageStatus(id) != StageStatus.WAITING_RETRY) {
            return;
        }
        if (!state.stopRequested() && !state.anyStage(StageStatus.FAILED)) {
            dispatch(definition.stage(id));
        }
        schedule();
    }

    // ---- approvals (OR-5, OR-14) ----

    private void requestApproval(String stageId, int attempt, ArtifactRef artifact, List<String> reasons) {
        String approvalId = UUID.randomUUID().toString();
        Instant expiresAt = now().plus(settings.approvalTtl());
        emit(seq -> new RunEvent.ApprovalRequested(runId, seq, now(), approvalId, stageId, attempt, artifact, reasons, expiresAt));
        arm("approval:" + approvalId, settings.approvalTtl(), new ApprovalExpired(approvalId));
    }

    private void onApprovalCommand(ApprovalCommand cmd) {
        Approval approval = state.approval(cmd.approvalId()).orElse(null);
        if (approval == null) {
            cmd.reply().completeExceptionally(new GovernanceException(Violation.APPROVAL_NOT_FOUND,
                    "No approval '" + cmd.approvalId() + "' in run " + runId));
            return;
        }
        if (approval.status() != ApprovalStatus.PENDING) {
            cmd.reply().completeExceptionally(new GovernanceException(Violation.APPROVAL_NOT_PENDING,
                    "Approval is already " + approval.status()));
            return;
        }
        if (cmd.actor().equals(state.initiator())) {
            cmd.reply().completeExceptionally(new GovernanceException(Violation.SELF_APPROVAL_FORBIDDEN,
                    "'" + cmd.actor() + "' started this run and cannot approve its checkpoints"));
            return;
        }
        if (!approval.artifact().contentHash().equals(cmd.artifactHash())) {
            cmd.reply().completeExceptionally(new GovernanceException(Violation.STALE_APPROVAL,
                    "Decision refers to artifact " + abbreviate(cmd.artifactHash()) + " but the pending artifact is "
                            + approval.artifact() + "; review the current version"));
            return;
        }

        cancelTimer("approval:" + approval.approvalId());
        String stageId = approval.stageId();
        if (cmd.approve()) {
            emit(seq -> new RunEvent.ApprovalDecided(runId, seq, now(), approval.approvalId(), stageId,
                    ApprovalStatus.APPROVED, cmd.actor(), cmd.comment()));
            emit(seq -> new RunEvent.StageSucceeded(runId, seq, now(), stageId, approval.attempt(), 0));
        } else {
            emit(seq -> new RunEvent.ApprovalDecided(runId, seq, now(), approval.approvalId(), stageId,
                    ApprovalStatus.REJECTED, cmd.actor(), cmd.comment()));
            emit(seq -> new RunEvent.StageFailed(runId, seq, now(), stageId, approval.attempt(),
                    FailureKind.APPROVAL_REJECTED, "rejected by " + cmd.actor()
                            + (cmd.comment() == null || cmd.comment().isBlank() ? "" : ": " + cmd.comment())));
        }
        cmd.reply().complete(state.approval(approval.approvalId()).orElseThrow());
        schedule();
    }

    private void onApprovalExpired(String approvalId) {
        timers.remove("approval:" + approvalId);
        Approval approval = state.approval(approvalId).orElse(null);
        if (approval == null || approval.status() != ApprovalStatus.PENDING) {
            return;
        }
        emit(seq -> new RunEvent.ApprovalDecided(runId, seq, now(), approvalId, approval.stageId(),
                ApprovalStatus.EXPIRED, SYSTEM, "no decision before " + approval.expiresAt()));
        emit(seq -> new RunEvent.StageFailed(runId, seq, now(), approval.stageId(), approval.attempt(),
                FailureKind.APPROVAL_EXPIRED, "approval expired at " + approval.expiresAt()));
        schedule();
    }

    // ---- re-planning (OR-12) ----

    /**
     * Sends back to PENDING every accepted (or awaiting-approval) stage whose output was derived
     * from an input version that is no longer current. Walks in topological order but only checks
     * <i>direct</i> provenance: a stage further downstream is re-evaluated only once its own input
     * actually re-runs and changes. If that re-run reproduces identical content, the hashes match
     * and the cascade stops there (early cutoff).
     */
    private void invalidateStaleStages() {
        for (String id : definition.topologicalOrder()) {
            StageStatus status = state.stageStatus(id);
            if (status != StageStatus.SUCCEEDED && status != StageStatus.AWAITING_APPROVAL) {
                continue;
            }
            Artifact artifact = state.latestArtifact(id);
            List<ArtifactRef> stale = artifact == null ? List.of() : staleInputs(artifact);
            if (stale.isEmpty()) {
                continue;
            }
            if (status == StageStatus.AWAITING_APPROVAL) {
                state.pendingApprovals().stream().filter(a -> a.stageId().equals(id)).forEach(a -> {
                    cancelTimer("approval:" + a.approvalId());
                    emit(seq -> new RunEvent.ApprovalDecided(runId, seq, now(), a.approvalId(), id,
                            ApprovalStatus.WITHDRAWN, SYSTEM, "superseded: the artifact under review is outdated"));
                });
            }
            String changes = String.join(", ", stale.stream().map(ref -> ref.stageId() + " v" + ref.version()
                    + " -> v" + state.latestArtifact(ref.stageId()).version()).toList());
            emit(seq -> new RunEvent.StageInvalidated(runId, seq, now(), id, stale, "input changed: " + changes));
        }
    }

    /** Inputs whose current content differs from the version this artifact was derived from. */
    private List<ArtifactRef> staleInputs(Artifact artifact) {
        return artifact.derivedFrom().stream().filter(ref -> {
            Artifact current = state.latestArtifact(ref.stageId());
            return current != null && !current.contentHash().equals(ref.contentHash());
        }).toList();
    }

    /**
     * A human replaces a stage's output. The revision passes the same policies as agent output
     * (a person can paste a secret too), needs approval under the same rules, and then re-planning
     * invalidates whatever consumed the old version.
     */
    private void onRevise(ReviseCommand cmd) {
        String id = cmd.stageId();
        if (!definition.hasStage(id) || state.stageStatus(id) != StageStatus.SUCCEEDED || windingDown()) {
            cmd.reply().completeExceptionally(new GovernanceException(Violation.STAGE_NOT_REVISABLE,
                    "Stage '" + id + "' cannot be revised now (only SUCCEEDED stages of an active run)"));
            return;
        }
        List<PolicyDecision> verdicts = policies.evaluate(new PolicyInput(definition.name(), id, state.requirement(),
                cmd.content(), mapper.writeValueAsString(cmd.content())));
        if (PolicyEngine.mostSevere(verdicts) == PolicyOutcome.BLOCK) {
            cmd.reply().completeExceptionally(new GovernanceException(Violation.REVISION_BLOCKED_BY_POLICY,
                    summarize(verdicts, PolicyOutcome.BLOCK)));
            return;
        }

        Artifact previous = state.latestArtifact(id);
        Artifact revised = new Artifact(id, previous == null ? 1 : previous.version() + 1, hasher.hash(cmd.content()),
                cmd.content(), now(), List.of());
        emit(seq -> new RunEvent.ArtifactRevised(runId, seq, now(), revised, cmd.actor(), cmd.reason()));
        int attempt = state.attempts(id);
        verdicts.forEach(p -> emit(seq -> new RunEvent.PolicyEvaluated(runId, seq, now(), id, attempt,
                p.policy(), p.category(), p.outcome(), p.reason())));

        List<String> approvalReasons = new ArrayList<>();
        StageDefinition stage = definition.stage(id);
        if (stage.policy().requiresApproval()) {
            approvalReasons.add("high-impact stage: " + stage.policy().approvalReason());
        }
        verdicts.stream().filter(p -> p.outcome() == PolicyOutcome.REQUIRE_APPROVAL)
                .forEach(p -> approvalReasons.add(p.category() + "/" + p.policy() + ": " + p.reason()));
        if (!approvalReasons.isEmpty()) {
            requestApproval(id, attempt, revised.ref(), approvalReasons);
        }
        cmd.reply().complete(revised);
        schedule();
    }

    // ---- safe-stop (OR-8) ----

    private void onStop(StopCommand cmd) {
        if (!state.stopRequested()) {
            requestStop(cmd.actor(), cmd.reason());
        }
        cmd.reply().complete(null);
        schedule();
    }

    private void requestStop(String actor, String reason) {
        emit(seq -> new RunEvent.StopRequested(runId, seq, now(), actor, reason));
    }

    // ---- scheduling ----

    /** Dispatches ready stages, winds down when failing/stopping, completes the run when idle. */
    private void schedule() {
        if (state.status().isTerminal() || compensating) {
            return;
        }
        if (state.rollbackPlan().isPresent()) {
            continueRollback();
            return;
        }
        if (!windingDown()) {
            invalidateStaleStages();
        }
        for (String id : definition.topologicalOrder()) {
            if (state.stageStatus(id) != StageStatus.PENDING) {
                continue;
            }
            String blocker = firstUnsuccessfulDependency(id);
            if (blocker != null) {
                emit(seq -> new RunEvent.StageSkipped(runId, seq, now(), id, "upstream stage '" + blocker + "' did not succeed"));
            } else if (!windingDown() && allDependenciesSucceeded(id)) {
                dispatch(definition.stage(id));
            }
        }
        if (windingDown()) {
            abandonWaitingWork();
        }
        if (!state.anyStageActive()) {
            finish();
        }
    }

    private boolean windingDown() {
        return state.stopRequested() || state.anyStage(StageStatus.FAILED);
    }

    /** Fail-fast / stop: queued retries and open approvals will never be acted on, so close them. */
    private void abandonWaitingWork() {
        String why = state.stopRequested() ? "run stopping" : "run failing";
        for (String id : state.stagesWithStatus(StageStatus.WAITING_RETRY)) {
            cancelTimer("retry:" + id);
            emit(seq -> new RunEvent.StageSkipped(runId, seq, now(), id, "retry abandoned: " + why));
        }
        for (Approval approval : state.pendingApprovals()) {
            cancelTimer("approval:" + approval.approvalId());
            emit(seq -> new RunEvent.ApprovalDecided(runId, seq, now(), approval.approvalId(), approval.stageId(),
                    ApprovalStatus.WITHDRAWN, SYSTEM, why));
            emit(seq -> new RunEvent.StageSkipped(runId, seq, now(), approval.stageId(), "approval withdrawn: " + why));
        }
    }

    private void finish() {
        if (state.stopRequested()) {
            skipPending("run stopped");
            emit(seq -> new RunEvent.RunCompleted(runId, seq, now(), RunStatus.STOPPED, "safe-stop: " + state.stopReason()));
        } else if (state.anyStage(StageStatus.FAILED)) {
            skipPending("run failed; no new stages are started after a failure");
            List<String> plan = new ArrayList<>(state.completionOrder().reversed().stream()
                    .filter(id -> definition.stage(id).policy().compensation() != null).toList());
            if (plan.isEmpty()) {
                emit(seq -> new RunEvent.RunCompleted(runId, seq, now(), RunStatus.FAILED, failureSummary()));
            } else {
                emit(seq -> new RunEvent.RollbackStarted(runId, seq, now(), plan));
                continueRollback();
            }
        } else if (state.allStages(StageStatus.SUCCEEDED)) {
            emit(seq -> new RunEvent.RunCompleted(runId, seq, now(), RunStatus.SUCCEEDED, "all stages succeeded"));
        } else {
            // Unreachable for a valid DAG; guards against a scheduling bug hanging the run.
            emit(seq -> new RunEvent.RunCompleted(runId, seq, now(), RunStatus.FAILED,
                    "no runnable stages left (scheduler invariant violated)"));
        }
    }

    private void skipPending(String reason) {
        for (String id : state.stagesWithStatus(StageStatus.PENDING)) {
            emit(seq -> new RunEvent.StageSkipped(runId, seq, now(), id, reason));
        }
    }

    // ---- rollback (OR-7) ----

    /** Compensates one stage at a time, in plan order; idempotent across restarts. */
    private void continueRollback() {
        if (compensating) {
            return;
        }
        String next = state.rollbackPlan().orElseThrow().stream()
                .filter(id -> state.stageStatus(id) == StageStatus.SUCCEEDED)
                .findFirst().orElse(null);
        if (next == null) {
            List<String> plan = state.rollbackPlan().orElseThrow();
            long failedCompensations = plan.stream().filter(id -> state.stageStatus(id) == StageStatus.ROLLBACK_FAILED).count();
            String reason = failureSummary() + "; rolled back " + (plan.size() - failedCompensations) + " stage(s)"
                    + (failedCompensations > 0 ? "; " + failedCompensations + " compensation(s) failed, manual cleanup required" : "");
            emit(seq -> new RunEvent.RunCompleted(runId, seq, now(), RunStatus.FAILED, reason));
            return;
        }
        compensating = true;
        var compensation = definition.stage(next).policy().compensation();
        CompensationContext context = new CompensationContext(runId, next, state.latestArtifact(next));
        agentExecutor.execute(() -> {
            try {
                post(new CompensationFinished(next, true, compensation.compensate(context)));
            } catch (Throwable t) {
                post(new CompensationFinished(next, false, "compensation failed: " + t));
            }
        });
    }

    private void onCompensationFinished(CompensationFinished done) {
        compensating = false;
        emit(seq -> new RunEvent.StageCompensated(runId, seq, now(), done.stageId(), done.succeeded(), done.detail()));
        continueRollback();
    }

    // ---- dispatch ----

    private void dispatch(StageDefinition stage) {
        String id = stage.id();
        int attempt = state.attempts(id) + 1;

        // Entry gates use their own context so their reads never pollute the agent's lineage.
        StageContext gateContext = contextFor(id, attempt);
        List<String> failures = new ArrayList<>();
        for (Gate gate : stage.entryGates()) {
            GateResult result = evaluate(gate, new GateInput(gateContext, null));
            emit(seq -> new RunEvent.GateEvaluated(runId, seq, now(), id, GateKind.ENTRY, gate.name(), result.passed(), result.reason()));
            if (!result.passed()) {
                failures.add(gate.name() + ": " + result.reason());
            }
        }
        if (!failures.isEmpty()) {
            emit(seq -> new RunEvent.StageFailed(runId, seq, now(), id, attempt, FailureKind.ENTRY_GATE, String.join("; ", failures)));
            return;
        }

        Agent agent = state.fallbackActive(id) ? stage.policy().fallback() : stage.agent();
        emit(seq -> new RunEvent.StageStarted(runId, seq, now(), id, attempt, agent.name()));
        StageContext context = contextFor(id, attempt);
        inflight.put(id, agentExecutor.submit(() -> {
            try {
                post(execute(stage, agent, context));
            } catch (Throwable fatal) {
                // Last resort (e.g. StackOverflowError): the coordinator must always hear back.
                post(new Errored(id, attempt, FailureKind.AGENT_ERROR, "fatal: " + fatal, 0));
                throw fatal;
            }
        }));
        arm("timeout:" + id, timeoutOf(stage), new AttemptTimedOut(id, attempt));
    }

    private StageContext contextFor(String stageId, int attempt) {
        Map<String, Artifact> visible = new HashMap<>();
        for (String ancestor : definition.ancestorsOf(stageId)) {
            Artifact artifact = state.latestArtifact(ancestor);
            if (artifact != null) {
                visible.put(ancestor, artifact);
            }
        }
        return new StageContext(runId, stageId, attempt, state.requirement(), visible,
                definition.ancestorsOf(stageId), clock, state::stopRequested);
    }

    // ------------------------------------------------------------------ worker threads

    /** Runs on a virtual thread. Never throws: every path produces an outcome for the mailbox. */
    private StageOutcome execute(StageDefinition stage, Agent agent, StageContext context) {
        String id = stage.id();
        int attempt = context.attempt();
        MDC.put("runId", runId);
        MDC.put("stageId", id);
        long started = System.nanoTime();
        try {
            AgentResult result = agent.execute(context);
            List<ArtifactRef> read = context.artifactsRead();
            JsonNode output = mapper.valueToTree(result.output());
            List<Decision> decisions = result.decisions().stream()
                    .map(d -> new Decision(id, attempt, agent.name(), d.summary(), d.rationale(), read, clock.instant()))
                    .toList();
            List<GateCheck> gates = stage.exitGates().stream().map(gate -> {
                GateResult r = evaluate(gate, new GateInput(context, output));
                return new GateCheck(gate.name(), r.passed(), r.reason());
            }).toList();
            List<PolicyDecision> verdicts = policies.evaluate(new PolicyInput(definition.name(), id,
                    context.requirement(), output, mapper.writeValueAsString(output)));
            return new Completed(id, attempt, output, read, decisions, gates, verdicts, elapsedMillis(started));
        } catch (ContextAccessException e) {
            return new Errored(id, attempt, FailureKind.CONTEXT_VIOLATION, e.getMessage(), elapsedMillis(started));
        } catch (Exception e) {
            log.warn("Agent {} failed on stage {} attempt {}: {}", agent.name(), id, attempt, describe(e));
            return new Errored(id, attempt, FailureKind.AGENT_ERROR, describe(e), elapsedMillis(started));
        } finally {
            MDC.remove("stageId");
            MDC.remove("runId");
        }
    }

    // ------------------------------------------------------------------ helpers

    private boolean isCurrentAttempt(String stageId, int attempt) {
        return state.stageStatus(stageId) == StageStatus.RUNNING && state.attempts(stageId) == attempt;
    }

    private void endAttempt(String stageId) {
        inflight.remove(stageId);
        cancelTimer("timeout:" + stageId);
    }

    private Duration timeoutOf(StageDefinition stage) {
        return stage.policy().timeout() != null ? stage.policy().timeout() : settings.defaultStageTimeout();
    }

    private void arm(String key, Duration delay, Signal signal) {
        cancelTimer(key);
        long millis = Math.max(0, delay.toMillis());
        timers.put(key, scheduler.schedule(() -> post(signal), millis, TimeUnit.MILLISECONDS));
    }

    private void cancelTimer(String key) {
        ScheduledFuture<?> timer = timers.remove(key);
        if (timer != null) {
            timer.cancel(false);
        }
    }

    /** After the run ends, commands still queued get a clear refusal instead of hanging their caller. */
    private void rejectPendingCommands() {
        Signal signal;
        while ((signal = mailbox.poll()) != null) {
            GovernanceException notActive = new GovernanceException(Violation.RUN_NOT_ACTIVE, "Run " + runId + " has finished");
            if (signal instanceof ApprovalCommand a) {
                a.reply().completeExceptionally(notActive);
            } else if (signal instanceof StopCommand s) {
                s.reply().completeExceptionally(notActive);
            } else if (signal instanceof ReviseCommand r) {
                r.reply().completeExceptionally(notActive);
            }
        }
    }

    private String failureSummary() {
        return "failed stages: " + state.stagesWithStatus(StageStatus.FAILED);
    }

    private String firstUnsuccessfulDependency(String id) {
        for (String dep : definition.stage(id).dependsOn()) {
            StageStatus s = state.stageStatus(dep);
            if (s == StageStatus.FAILED || s == StageStatus.SKIPPED) {
                return dep;
            }
        }
        return null;
    }

    private boolean allDependenciesSucceeded(String id) {
        return definition.stage(id).dependsOn().stream().allMatch(dep -> state.stageStatus(dep) == StageStatus.SUCCEEDED);
    }

    private static String summarize(List<PolicyDecision> decisions, PolicyOutcome outcome) {
        return String.join("; ", decisions.stream().filter(p -> p.outcome() == outcome)
                .map(p -> p.category() + "/" + p.policy() + ": " + p.reason()).toList());
    }

    /** Gates fail closed: an exception or a null result counts as a failed gate. */
    private static GateResult evaluate(Gate gate, GateInput input) {
        try {
            GateResult result = gate.evaluate(input);
            return result != null ? result : GateResult.fail("gate returned no result");
        } catch (RuntimeException e) {
            return GateResult.fail("gate threw " + describe(e));
        }
    }

    private static String describe(Throwable e) {
        return e.getClass().getSimpleName() + (e.getMessage() != null ? ": " + e.getMessage() : "");
    }

    private static String abbreviate(String hash) {
        return hash == null ? "(none)" : hash.substring(0, Math.min(12, hash.length()));
    }

    private static long elapsedMillis(long startedNanos) {
        return (System.nanoTime() - startedNanos) / 1_000_000;
    }

    private Instant now() {
        return clock.instant();
    }

    /** Append to the log first, then apply: state is never ahead of the durable record. */
    private void emit(LongFunction<RunEvent> factory) {
        RunEvent event = factory.apply(state.lastSeq() + 1);
        store.append(event);
        state.apply(event);
    }
}
