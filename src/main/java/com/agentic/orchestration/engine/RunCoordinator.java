package com.agentic.orchestration.engine;

import com.agentic.orchestration.agent.AgentResult;
import com.agentic.orchestration.agent.ContextAccessException;
import com.agentic.orchestration.agent.StageContext;
import com.agentic.orchestration.definition.Gate;
import com.agentic.orchestration.definition.GateInput;
import com.agentic.orchestration.definition.GateResult;
import com.agentic.orchestration.definition.StageDefinition;
import com.agentic.orchestration.definition.WorkflowDefinition;
import com.agentic.orchestration.engine.StageOutcome.Completed;
import com.agentic.orchestration.engine.StageOutcome.Errored;
import com.agentic.orchestration.engine.StageOutcome.GateCheck;
import com.agentic.orchestration.event.FailureKind;
import com.agentic.orchestration.event.GateKind;
import com.agentic.orchestration.event.RunEvent;
import com.agentic.orchestration.event.RunEventStore;
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
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.function.LongFunction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Drives one workflow run. An <b>actor</b>: a single coordinator thread owns the run's state and
 * processes {@link Signal}s from a mailbox one at a time, while agents execute concurrently on
 * a shared virtual-thread pool and report back by posting {@link StageOutcome}s.
 *
 * <p>Consequences of this design:
 * <ul>
 *   <li>No locks around workflow state and no races between parallel stages: only this thread
 *       writes, and it handles one fact at a time.</li>
 *   <li><b>Write-ahead events:</b> every change is appended to the {@link RunEventStore} first and
 *       then applied to {@link RunState}; state is never ahead of the log.</li>
 *   <li>Fan-in synchronisation (OR-3) is implicit: a stage is dispatched only when <i>all</i> of its
 *       dependencies have SUCCEEDED, re-checked after every outcome.</li>
 * </ul>
 *
 * <p>Failure policy: <b>fail fast</b>. After any stage fails, no new stage is started; in-flight
 * stages finish and are recorded; everything still pending is SKIPPED, and the run FAILS.
 */
final class RunCoordinator {

    private static final Logger log = LoggerFactory.getLogger(RunCoordinator.class);

    /** Messages the coordinator reacts to. Stage outcomes now; approvals and stop requests later. */
    sealed interface Signal permits Schedule, StageOutcome {
    }

    record Schedule() implements Signal {
    }

    private final String runId;
    private final WorkflowDefinition definition;
    private final RunEventStore store;
    private final ExecutorService agentExecutor;
    private final JsonMapper mapper;
    private final ContentHasher hasher;
    private final Clock clock;
    private final RunState state = new RunState();
    private final BlockingQueue<Signal> mailbox = new LinkedBlockingQueue<>();
    private final CompletableFuture<RunView> completion = new CompletableFuture<>();
    private volatile Thread thread;

    RunCoordinator(String runId, WorkflowDefinition definition, RunEventStore store, ExecutorService agentExecutor,
                   JsonMapper mapper, Clock clock) {
        this.runId = runId;
        this.definition = definition;
        this.store = store;
        this.agentExecutor = agentExecutor;
        this.mapper = mapper;
        this.hasher = new ContentHasher(mapper);
        this.clock = clock;
    }

    void start(Requirement requirement, String initiator) {
        emit(seq -> new RunEvent.RunStarted(runId, seq, clock.instant(), definition.name(), definition.version(),
                requirement, initiator, definition.topologicalOrder()));
        mailbox.add(new Schedule());
        thread = Thread.ofVirtual().name("run-" + runId).start(this::loop);
    }

    /**
     * Continues a run from its persisted history after a restart (OR-15): rebuild state by
     * replay, record which stages were interrupted, then schedule as usual.
     */
    void resume(List<RunEvent> history) {
        history.forEach(state::apply);
        List<String> interrupted = state.stagesWithStatus(StageStatus.RUNNING);
        emit(seq -> new RunEvent.RunResumed(runId, seq, clock.instant(), interrupted));
        mailbox.add(new Schedule());
        thread = Thread.ofVirtual().name("run-" + runId).start(this::loop);
    }

    /**
     * Stops the coordinator <b>without writing anything</b>, exactly as if the process died.
     * Shutdown and crash therefore leave the same durable state, and recovery is one tested path.
     */
    void stop() throws InterruptedException {
        Thread t = thread;
        if (t != null) {
            t.interrupt();
            t.join(Duration.ofSeconds(5));
        }
    }

    RunView view() {
        return state.view();
    }

    CompletableFuture<RunView> completion() {
        return completion;
    }

    // ------------------------------------------------------------------ coordinator thread

    private void loop() {
        MDC.put("runId", runId);
        try {
            while (!state.status().isTerminal()) {
                Signal signal = mailbox.take();
                handle(signal);
            }
            completion.complete(state.view());
        } catch (InterruptedException e) {
            // Shutdown: leave the run as RUNNING in the store; it is resumed on next start.
            Thread.currentThread().interrupt();
            completion.completeExceptionally(new IllegalStateException("engine stopped; run " + runId + " will resume on restart"));
        } catch (RuntimeException e) {
            // A coordinator bug must not leave callers waiting forever.
            log.error("Coordinator for run {} crashed", runId, e);
            completion.completeExceptionally(e);
        } finally {
            MDC.remove("runId");
        }
    }

    private void handle(Signal signal) {
        switch (signal) {
            case Schedule s -> schedule();
            case Completed c -> onCompleted(c);
            case Errored e -> onErrored(e);
        }
    }

    private void onCompleted(Completed outcome) {
        String stageId = outcome.stageId();
        Artifact previous = state.latestArtifact(stageId);
        int version = previous == null ? 1 : previous.version() + 1;
        Artifact artifact = new Artifact(stageId, version, hasher.hash(outcome.output()), outcome.output(), clock.instant());

        emit(seq -> new RunEvent.ArtifactProduced(runId, seq, clock.instant(), artifact));
        outcome.decisions().forEach(d -> emit(seq -> new RunEvent.DecisionRecorded(runId, seq, clock.instant(), d)));
        outcome.exitGates().forEach(g -> emit(seq -> new RunEvent.GateEvaluated(runId, seq, clock.instant(),
                stageId, GateKind.EXIT, g.gate(), g.passed(), g.reason())));

        List<String> failedGates = outcome.exitGates().stream().filter(g -> !g.passed())
                .map(g -> g.gate() + ": " + g.reason()).toList();
        if (failedGates.isEmpty()) {
            emit(seq -> new RunEvent.StageSucceeded(runId, seq, clock.instant(), stageId, outcome.attempt(),
                    outcome.durationMillis()));
        } else {
            emit(seq -> new RunEvent.StageFailed(runId, seq, clock.instant(), stageId, outcome.attempt(),
                    FailureKind.EXIT_GATE, String.join("; ", failedGates)));
        }
        schedule();
    }

    private void onErrored(Errored outcome) {
        emit(seq -> new RunEvent.StageFailed(runId, seq, clock.instant(), outcome.stageId(), outcome.attempt(),
                outcome.kind(), outcome.reason()));
        schedule();
    }

    /** Dispatches every stage whose dependencies are satisfied; completes the run when nothing is left. */
    private void schedule() {
        if (state.status().isTerminal()) {
            return;
        }
        for (String id : definition.topologicalOrder()) {
            if (state.stageStatus(id) != StageStatus.PENDING) {
                continue;
            }
            String blocker = firstUnsuccessfulDependency(id);
            if (blocker != null) {
                emit(seq -> new RunEvent.StageSkipped(runId, seq, clock.instant(), id,
                        "upstream stage '" + blocker + "' did not succeed"));
            } else if (!state.anyStage(StageStatus.FAILED) && allDependenciesSucceeded(id)) {
                dispatch(definition.stage(id));
            }
        }
        if (!state.anyStage(StageStatus.RUNNING)) {
            finish();
        }
    }

    private void finish() {
        if (state.anyStage(StageStatus.FAILED)) {
            for (String id : definition.topologicalOrder()) {
                if (state.stageStatus(id) == StageStatus.PENDING) {
                    emit(seq -> new RunEvent.StageSkipped(runId, seq, clock.instant(), id,
                            "run failed; no new stages are started after a failure"));
                }
            }
            String failed = definition.topologicalOrder().stream()
                    .filter(id -> state.stageStatus(id) == StageStatus.FAILED).toList().toString();
            emit(seq -> new RunEvent.RunCompleted(runId, seq, clock.instant(), RunStatus.FAILED,
                    "failed stages: " + failed));
        } else if (state.allStages(StageStatus.SUCCEEDED)) {
            emit(seq -> new RunEvent.RunCompleted(runId, seq, clock.instant(), RunStatus.SUCCEEDED, "all stages succeeded"));
        } else {
            // Unreachable for a valid DAG; guards against a scheduling bug hanging the run.
            emit(seq -> new RunEvent.RunCompleted(runId, seq, clock.instant(), RunStatus.FAILED,
                    "no runnable stages left (scheduler invariant violated)"));
        }
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

    private void dispatch(StageDefinition stage) {
        String id = stage.id();
        int attempt = state.attempts(id) + 1;

        // Entry gates see the same inputs the agent will, but through their own context so that
        // reads made by gates never pollute the agent's decision lineage.
        StageContext gateContext = contextFor(id, attempt);
        List<String> failures = new ArrayList<>();
        for (Gate gate : stage.entryGates()) {
            GateResult result = evaluate(gate, new GateInput(gateContext, null));
            emit(seq -> new RunEvent.GateEvaluated(runId, seq, clock.instant(), id, GateKind.ENTRY,
                    gate.name(), result.passed(), result.reason()));
            if (!result.passed()) {
                failures.add(gate.name() + ": " + result.reason());
            }
        }
        if (!failures.isEmpty()) {
            emit(seq -> new RunEvent.StageFailed(runId, seq, clock.instant(), id, attempt,
                    FailureKind.ENTRY_GATE, String.join("; ", failures)));
            return;
        }

        emit(seq -> new RunEvent.StageStarted(runId, seq, clock.instant(), id, attempt, stage.agent().name()));
        StageContext context = contextFor(id, attempt);
        agentExecutor.execute(() -> {
            try {
                mailbox.add(execute(stage, context));
            } catch (Throwable fatal) {
                // Last resort (e.g. StackOverflowError in an agent): the coordinator must always
                // hear back, or the run would wait forever on a stage that silently died.
                mailbox.add(new Errored(id, attempt, FailureKind.AGENT_ERROR, "fatal: " + fatal, 0));
                throw fatal;
            }
        });
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
                definition.ancestorsOf(stageId), clock, () -> false);
    }

    // ------------------------------------------------------------------ worker threads

    /** Runs on a virtual thread. Never throws: every path produces an outcome for the mailbox. */
    private StageOutcome execute(StageDefinition stage, StageContext context) {
        String id = stage.id();
        int attempt = context.attempt();
        MDC.put("runId", runId);
        MDC.put("stageId", id);
        long started = System.nanoTime();
        try {
            AgentResult result = stage.agent().execute(context);
            List<ArtifactRef> read = context.artifactsRead();
            JsonNode output = mapper.valueToTree(result.output());
            List<Decision> decisions = result.decisions().stream()
                    .map(d -> new Decision(id, attempt, stage.agent().name(), d.summary(), d.rationale(), read, clock.instant()))
                    .toList();
            List<GateCheck> gates = stage.exitGates().stream().map(gate -> {
                GateResult r = evaluate(gate, new GateInput(context, output));
                return new GateCheck(gate.name(), r.passed(), r.reason());
            }).toList();
            return new Completed(id, attempt, output, decisions, gates, elapsedMillis(started));
        } catch (ContextAccessException e) {
            return new Errored(id, attempt, FailureKind.CONTEXT_VIOLATION, e.getMessage(), elapsedMillis(started));
        } catch (Exception e) {
            log.warn("Agent {} failed on stage {} attempt {}", stage.agent().name(), id, attempt, e);
            return new Errored(id, attempt, FailureKind.AGENT_ERROR, describe(e), elapsedMillis(started));
        } finally {
            MDC.remove("stageId");
            MDC.remove("runId");
        }
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

    private static String describe(Exception e) {
        return e.getClass().getSimpleName() + (e.getMessage() != null ? ": " + e.getMessage() : "");
    }

    private static long elapsedMillis(long startedNanos) {
        return (System.nanoTime() - startedNanos) / 1_000_000;
    }

    /** Append to the log first, then apply: state is never ahead of the durable record. */
    private void emit(LongFunction<RunEvent> factory) {
        RunEvent event = factory.apply(state.lastSeq() + 1);
        store.append(event);
        state.apply(event);
    }
}
