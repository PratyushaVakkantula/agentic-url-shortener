package com.agentic.orchestration.engine;

import static com.agentic.orchestration.engine.TestAgents.agent;
import static com.agentic.orchestration.engine.TestAgents.computing;
import static com.agentic.orchestration.engine.TestAgents.emitting;
import static com.agentic.orchestration.engine.TestAgents.failing;
import static org.assertj.core.api.Assertions.assertThat;

import com.agentic.orchestration.agent.AgentResult;
import com.agentic.orchestration.definition.Gate;
import com.agentic.orchestration.definition.Gates;
import com.agentic.orchestration.definition.WorkflowDefinition;
import com.agentic.orchestration.event.FailureKind;
import com.agentic.orchestration.event.InMemoryRunEventStore;
import com.agentic.orchestration.event.RunEvent;
import com.agentic.orchestration.model.Artifact;
import com.agentic.orchestration.model.Decision;
import com.agentic.orchestration.model.Requirement;
import com.agentic.orchestration.model.RunStatus;
import com.agentic.orchestration.model.StageStatus;
import com.agentic.orchestration.state.RunState;
import com.agentic.orchestration.state.RunView;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import tools.jackson.databind.json.JsonMapper;

class WorkflowEngineTest {

    private static final Duration WAIT = Duration.ofSeconds(10);
    private static final Requirement REQ = new Requirement("Add expiry to links", "Links should expire.");

    private final InMemoryRunEventStore store = new InMemoryRunEventStore();
    private final WorkflowEngine engine = new WorkflowEngine(store, new WorkflowCatalog(List.of()), JsonMapper.builder().build(), Clock.systemUTC());

    @AfterEach
    void shutdown() {
        engine.shutdown();
    }

    private RunView run(WorkflowDefinition wf) throws Exception {
        String runId = engine.start(wf, REQ, "alice");
        return engine.awaitCompletion(runId, WAIT);
    }

    private static List<Class<?>> eventTypes(List<RunEvent> events) {
        return events.stream().<Class<?>>map(Object::getClass).toList();
    }

    @Test
    void sequentialChainPassesArtifactsDownstream() throws Exception {
        WorkflowDefinition wf = WorkflowDefinition.builder("chain", 1)
                .stage("requirements", emitting("req-agent", Map.of("tasks", List.of("schema", "api")))).add()
                .stage("design", computing("design-agent",
                        ctx -> Map.of("taskCount", ctx.artifact("requirements").content().get("tasks").size())))
                        .dependsOn("requirements").add()
                .build();

        RunView view = run(wf);

        assertThat(view.status()).isEqualTo(RunStatus.SUCCEEDED);
        assertThat(view.latestArtifact("design").orElseThrow().content().get("taskCount").asInt()).isEqualTo(2);
        assertThat(view.initiator()).isEqualTo("alice");
        assertThat(eventTypes(engine.events(view.runId()))).containsExactly(
                RunEvent.RunStarted.class,
                RunEvent.StageStarted.class, RunEvent.ArtifactProduced.class, RunEvent.StageSucceeded.class,
                RunEvent.StageStarted.class, RunEvent.ArtifactProduced.class, RunEvent.StageSucceeded.class,
                RunEvent.RunCompleted.class);
    }

    /**
     * b and c each wait at a 2-party barrier: the test can only pass if both run at the same time.
     * d must start strictly after both have succeeded (fan-in synchronisation).
     */
    @Test
    void parallelBranchesRunConcurrentlyAndJoinWaitsForBoth() throws Exception {
        CyclicBarrier bothRunning = new CyclicBarrier(2);
        WorkflowDefinition wf = WorkflowDefinition.builder("diamond", 1)
                .stage("a", emitting("a", Map.of("v", 1))).add()
                .stage("b", agent("b", ctx -> {
                    bothRunning.await(5, TimeUnit.SECONDS);
                    return AgentResult.of(Map.of("v", 2));
                })).dependsOn("a").add()
                .stage("c", agent("c", ctx -> {
                    bothRunning.await(5, TimeUnit.SECONDS);
                    return AgentResult.of(Map.of("v", 3));
                })).dependsOn("a").add()
                .stage("d", computing("d", ctx -> Map.of("sum",
                        ctx.artifact("b").content().get("v").asInt() + ctx.artifact("c").content().get("v").asInt())))
                        .dependsOn("b", "c").add()
                .build();

        RunView view = run(wf);

        assertThat(view.status()).isEqualTo(RunStatus.SUCCEEDED);
        assertThat(view.latestArtifact("d").orElseThrow().content().get("sum").asInt()).isEqualTo(5);

        List<RunEvent> events = engine.events(view.runId());
        long dStarted = seqOf(events, RunEvent.StageStarted.class, "d");
        assertThat(dStarted).isGreaterThan(seqOf(events, RunEvent.StageSucceeded.class, "b"));
        assertThat(dStarted).isGreaterThan(seqOf(events, RunEvent.StageSucceeded.class, "c"));
        // b and c were both dispatched before either finished
        assertThat(seqOf(events, RunEvent.StageStarted.class, "c")).isLessThan(seqOf(events, RunEvent.StageSucceeded.class, "b"));
    }

    @Test
    void failedEntryGateFailsStageWithoutRunningItAndSkipsDownstream() throws Exception {
        WorkflowDefinition wf = WorkflowDefinition.builder("gated", 1)
                .stage("design", emitting("design", Map.of("modules", List.of()))).add()
                .stage("implement", emitting("impl", Map.of("ok", true))).dependsOn("design")
                        .entryGate(Gates.upstreamHas("design", "modules")).add()
                .stage("docs", emitting("docs", Map.of("ok", true))).dependsOn("implement").add()
                .build();

        RunView view = run(wf);

        assertThat(view.status()).isEqualTo(RunStatus.FAILED);
        RunView.StageView implement = view.stage("implement");
        assertThat(implement.status()).isEqualTo(StageStatus.FAILED);
        assertThat(implement.lastFailureKind()).isEqualTo(FailureKind.ENTRY_GATE);
        assertThat(implement.attempts()).as("agent never ran").isZero();
        assertThat(implement.lastFailure()).contains("did not provide 'modules'");
        assertThat(view.stage("docs").status()).isEqualTo(StageStatus.SKIPPED);
        assertThat(view.stage("docs").lastFailure()).contains("upstream stage 'implement' did not succeed");
    }

    @Test
    void failedExitGateRejectsOutputButKeepsItForAudit() throws Exception {
        WorkflowDefinition wf = WorkflowDefinition.builder("exit", 1)
                .stage("test", emitting("tester", Map.of("failures", 2)))
                        .exitGate(Gates.outputField("failures", n -> n.asInt() == 0, "tests must pass")).add()
                .build();

        RunView view = run(wf);

        assertThat(view.status()).isEqualTo(RunStatus.FAILED);
        assertThat(view.stage("test").lastFailureKind()).isEqualTo(FailureKind.EXIT_GATE);
        assertThat(view.stage("test").lastFailure()).contains("tests must pass");
        assertThat(view.artifacts()).hasSize(1); // rejected output is still on record
    }

    @Test
    void gatesFailClosedWhenTheyThrow() throws Exception {
        Gate broken = Gate.of("broken", in -> {
            throw new IllegalStateException("bug in gate");
        }, "unused");
        WorkflowDefinition wf = WorkflowDefinition.builder("broken-gate", 1)
                .stage("a", emitting("a", Map.of())).exitGate(broken).add()
                .build();

        RunView view = run(wf);

        assertThat(view.stage("a").status()).isEqualTo(StageStatus.FAILED);
        assertThat(view.stage("a").lastFailure()).contains("gate threw IllegalStateException: bug in gate");
    }

    /** Fail-fast: no new stages after a failure, but work already in flight completes and is recorded. */
    @Test
    void failureStopsNewDispatchButLetsInFlightSiblingFinish() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        WorkflowDefinition wf = WorkflowDefinition.builder("partial", 1)
                .stage("a", emitting("a", Map.of())).add()
                .stage("b", failing("b", "compile error")).dependsOn("a").add()
                .stage("c", agent("c", ctx -> {
                    release.await(5, TimeUnit.SECONDS);
                    return AgentResult.of(Map.of("done", true));
                })).dependsOn("a").add()
                .stage("d", emitting("d", Map.of())).dependsOn("b", "c").add()
                .stage("e", emitting("e", Map.of())).dependsOn("c").add()
                .build();

        String runId = engine.start(wf, REQ, "alice");
        awaitStage(runId, "b", StageStatus.FAILED);
        release.countDown();
        RunView view = engine.awaitCompletion(runId, WAIT);

        assertThat(view.status()).isEqualTo(RunStatus.FAILED);
        assertThat(view.stage("b").lastFailure()).isEqualTo("IllegalStateException: compile error");
        assertThat(view.stage("c").status()).isEqualTo(StageStatus.SUCCEEDED);
        assertThat(view.stage("d").status()).isEqualTo(StageStatus.SKIPPED);
        assertThat(view.stage("e").status()).as("independent of b, but not started after the failure")
                .isEqualTo(StageStatus.SKIPPED);
    }

    @Test
    void agentMayNotReadArtifactsOutsideItsAncestors() throws Exception {
        WorkflowDefinition wf = WorkflowDefinition.builder("isolation", 1)
                .stage("a", emitting("a", Map.of())).add()
                .stage("b", computing("b", ctx -> ctx.artifact("c").content())).dependsOn("a").add()
                .stage("c", emitting("c", Map.of())).dependsOn("a").add()
                .build();

        RunView view = run(wf);

        assertThat(view.stage("b").lastFailureKind()).isEqualTo(FailureKind.CONTEXT_VIOLATION);
        assertThat(view.stage("b").lastFailure()).contains("may only read artifacts of its ancestors");
    }

    @Test
    void decisionLineageListsExactlyTheArtifactVersionsTheAgentRead() throws Exception {
        WorkflowDefinition wf = WorkflowDefinition.builder("lineage", 1)
                .stage("a", emitting("a", Map.of("x", 1))).add()
                .stage("b", emitting("b", Map.of("y", 2))).dependsOn("a").add()
                .stage("c", agent("c-agent", ctx -> {
                    int y = ctx.artifact("b").content().get("y").asInt(); // reads b only, although a is visible too
                    return AgentResult.of(Map.of("z", y * 10))
                            .withDecision("scale y by 10", "design doc says amounts are in tenths");
                })).dependsOn("b").add()
                .build();

        RunView view = run(wf);

        Decision decision = view.decisions().getFirst();
        Artifact b = view.latestArtifact("b").orElseThrow();
        assertThat(decision.stageId()).isEqualTo("c");
        assertThat(decision.agent()).isEqualTo("c-agent");
        assertThat(decision.rationale()).isEqualTo("design doc says amounts are in tenths");
        assertThat(decision.basedOn()).containsExactly(b.ref());
        assertThat(b.ref().version()).isEqualTo(1);
    }

    @Test
    void replayingTheEventLogReproducesTheLiveState() throws Exception {
        WorkflowDefinition wf = WorkflowDefinition.builder("replay", 1)
                .stage("a", agent("a", ctx -> AgentResult.of(Map.of("x", 1)).withDecision("d1", "r1"))).add()
                .stage("b", failing("b", "boom")).dependsOn("a").add()
                .stage("c", emitting("c", Map.of())).dependsOn("b").add()
                .build();

        RunView live = run(wf);
        RunView replayed = RunState.replay(engine.events(live.runId())).view();

        assertThat(replayed).isEqualTo(live);
    }

    @Test
    void agentThreadsCarryTheRunIdForLogCorrelation() throws Exception {
        WorkflowDefinition wf = WorkflowDefinition.builder("mdc", 1)
                .stage("a", computing("a", ctx -> Map.of("mdcRun", String.valueOf(MDC.get("runId")),
                        "mdcStage", String.valueOf(MDC.get("stageId"))))).add()
                .build();

        RunView view = run(wf);

        var output = view.latestArtifact("a").orElseThrow().content();
        assertThat(output.get("mdcRun").asString()).isEqualTo(view.runId());
        assertThat(output.get("mdcStage").asString()).isEqualTo("a");
    }

    @Test
    void fatalErrorInAgentFailsTheRunInsteadOfHangingIt() throws Exception {
        WorkflowDefinition wf = WorkflowDefinition.builder("fatal", 1)
                .stage("a", agent("a", ctx -> {
                    throw new StackOverflowError("simulated");
                })).add()
                .build();

        RunView view = run(wf);

        assertThat(view.status()).isEqualTo(RunStatus.FAILED);
        assertThat(view.stage("a").lastFailure()).contains("fatal").contains("StackOverflowError");
    }

    private static long seqOf(List<RunEvent> events, Class<? extends RunEvent> type, String stageId) {
        return events.stream()
                .filter(type::isInstance)
                .filter(e -> stageId.equals(switch (e) {
                    case RunEvent.StageStarted s -> s.stageId();
                    case RunEvent.StageSucceeded s -> s.stageId();
                    default -> null;
                }))
                .mapToLong(RunEvent::seq).findFirst().orElseThrow();
    }

    private void awaitStage(String runId, String stageId, StageStatus status) throws InterruptedException {
        long deadline = System.nanoTime() + WAIT.toNanos();
        while (engine.find(runId).orElseThrow().stage(stageId).status() != status) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError("stage " + stageId + " never reached " + status);
            }
            Thread.sleep(5);
        }
    }
}
