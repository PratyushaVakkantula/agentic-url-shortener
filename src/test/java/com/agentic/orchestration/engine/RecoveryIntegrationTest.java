package com.agentic.orchestration.engine;

import static com.agentic.orchestration.engine.TestAgents.agent;
import static com.agentic.orchestration.engine.TestAgents.emitting;
import static com.agentic.orchestration.engine.TestAgents.failing;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.agentic.orchestration.agent.AgentResult;
import com.agentic.orchestration.definition.Gates;
import com.agentic.orchestration.definition.WorkflowDefinition;
import com.agentic.orchestration.event.ConcurrentRunModificationException;
import com.agentic.orchestration.event.RunEvent;
import com.agentic.orchestration.event.RunEventStore;
import com.agentic.orchestration.event.RunSummary;
import com.agentic.orchestration.governance.PolicyEngine;
import com.agentic.orchestration.model.Requirement;
import com.agentic.orchestration.model.RunStatus;
import com.agentic.orchestration.model.StageStatus;
import com.agentic.orchestration.state.RunState;
import com.agentic.orchestration.state.RunView;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import tools.jackson.databind.json.JsonMapper;

/**
 * Durability and recovery against the real database store (OR-15). Each "process" is a separate
 * {@link WorkflowEngine} instance sharing only the database, exactly like a restart.
 */
@SpringBootTest
@ActiveProfiles("test")
class RecoveryIntegrationTest {

    private static final Duration WAIT = Duration.ofSeconds(10);
    private static final Requirement REQ = new Requirement("Add click limits", "Stop redirecting after N clicks");

    @Autowired
    RunEventStore store;

    @Autowired
    JsonMapper mapper;

    private final List<WorkflowEngine> engines = new ArrayList<>();

    @AfterEach
    void stopEngines() {
        engines.forEach(WorkflowEngine::shutdown);
    }

    private WorkflowEngine process(WorkflowDefinition... deployed) {
        WorkflowEngine engine = new WorkflowEngine(store, new WorkflowCatalog(List.of(deployed)), PolicyEngine.none(),
                new GovernanceSettings(Duration.ofSeconds(30), Duration.ofHours(1)), mapper, Clock.systemUTC());
        engines.add(engine);
        return engine;
    }

    @Test
    void everyEventTypeRoundTripsThroughTheDatabase() throws Exception {
        WorkflowDefinition wf = WorkflowDefinition.builder("roundtrip", 1)
                .stage("plan", agent("planner", ctx -> AgentResult.of(Map.of("tasks", List.of("a", "b"), "nested", Map.of("k", 1.5)))
                        .withDecision("split into 2 tasks", "two modules affected"))).add()
                .stage("build", failing("builder", "compile error")).dependsOn("plan")
                        .entryGate(Gates.upstreamHas("plan", "tasks")).add()
                .stage("docs", emitting("writer", Map.of())).dependsOn("build").add()
                .build();
        WorkflowEngine engine = process(wf);

        String runId = engine.start(wf, REQ, "alice");
        RunView live = engine.awaitCompletion(runId, WAIT);

        RunView fromDatabase = RunState.replay(store.load(runId)).view();
        assertThat(fromDatabase).isEqualTo(live);
        RunSummary summary = store.runs().stream().filter(r -> r.runId().equals(runId)).findFirst().orElseThrow();
        assertThat(summary.status()).isEqualTo(RunStatus.FAILED);
        assertThat(summary.initiator()).isEqualTo("alice");
        assertThat(summary.title()).isEqualTo("Add click limits");
        assertThat(summary.eventCount()).isEqualTo(store.load(runId).size());
        assertThat(summary.finishedAt()).isNotNull();
    }

    @Test
    void storeRejectsASecondWriterAndSequenceGaps() throws Exception {
        WorkflowDefinition wf = WorkflowDefinition.builder("single-writer", 1).stage("a", emitting("a", Map.of())).add().build();
        WorkflowEngine engine = process(wf);
        String runId = engine.start(wf, REQ, "alice");
        engine.awaitCompletion(runId, WAIT);
        long last = store.load(runId).size();

        RunEvent duplicate = new RunEvent.RunCompleted(runId, last, Instant.now(), RunStatus.FAILED, "rogue writer");
        RunEvent gap = new RunEvent.RunCompleted(runId, last + 2, Instant.now(), RunStatus.FAILED, "rogue writer");

        assertThatThrownBy(() -> store.append(duplicate)).isInstanceOf(ConcurrentRunModificationException.class);
        assertThatThrownBy(() -> store.append(gap)).isInstanceOf(ConcurrentRunModificationException.class);
        assertThat(store.load(runId)).hasSize((int) last);
    }

    /**
     * The headline recovery scenario: the process dies while a stage is executing. A new process
     * resumes the run from the database, re-runs only the interrupted stage, and completes it.
     */
    @Test
    void runInterruptedMidStageIsResumedByTheNextProcess() throws Exception {
        AtomicInteger planCalls = new AtomicInteger();
        AtomicInteger buildCalls = new AtomicInteger();
        CountDownLatch buildStarted = new CountDownLatch(1);
        WorkflowDefinition wf = WorkflowDefinition.builder("recoverable", 1)
                .stage("plan", agent("planner", ctx -> {
                    planCalls.incrementAndGet();
                    return AgentResult.of(Map.of("tasks", List.of("x")));
                })).add()
                .stage("build", agent("builder", ctx -> {
                    if (buildCalls.incrementAndGet() == 1) {
                        buildStarted.countDown();
                        new CountDownLatch(1).await(); // first process: hangs until the process "dies"
                    }
                    return AgentResult.of(Map.of("built", true));
                })).dependsOn("plan").add()
                .stage("docs", emitting("writer", Map.of("ok", true))).dependsOn("build").add()
                .build();

        WorkflowEngine first = process(wf);
        String runId = first.start(wf, REQ, "alice");
        buildStarted.await();
        first.shutdown(); // simulated crash: nothing is written on the way down

        assertThat(store.runIdsWithStatus(RunStatus.RUNNING)).contains(runId);
        assertThat(RunState.replay(store.load(runId)).view().stage("build").status()).isEqualTo(StageStatus.RUNNING);

        WorkflowEngine second = process(wf);
        assertThat(second.resumeUnfinishedRuns()).contains(runId);
        RunView done = second.awaitCompletion(runId, WAIT);

        assertThat(done.status()).isEqualTo(RunStatus.SUCCEEDED);
        assertThat(done.stage("build").attempts()).isEqualTo(2);
        assertThat(planCalls.get()).as("completed stages are not redone").isEqualTo(1);
        assertThat(store.load(runId)).filteredOn(RunEvent.RunResumed.class::isInstance)
                .singleElement()
                .satisfies(e -> assertThat(((RunEvent.RunResumed) e).interruptedStages()).containsExactly("build"));
        assertThat(store.runIdsWithStatus(RunStatus.RUNNING)).doesNotContain(runId);
    }

    @Test
    void runIsAbandonedWhenItsWorkflowVersionChangedAcrossTheRestart() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        WorkflowDefinition v1 = WorkflowDefinition.builder("evolving", 1)
                .stage("a", agent("a", ctx -> {
                    started.countDown();
                    new CountDownLatch(1).await();
                    return AgentResult.of(Map.of());
                })).add()
                .build();
        WorkflowDefinition v2 = WorkflowDefinition.builder("evolving", 2).stage("a", emitting("a", Map.of())).add().build();

        WorkflowEngine first = process(v1);
        String runId = first.start(v1, REQ, "alice");
        started.await();
        first.shutdown();

        WorkflowEngine second = process(v2);
        assertThat(second.resumeUnfinishedRuns()).doesNotContain(runId);

        RunView view = second.get(runId);
        assertThat(view.status()).isEqualTo(RunStatus.FAILED);
        assertThat(view.statusReason()).contains("evolving v1 is no longer deployed");
    }

    /** A checkpoint can be open for days; a deploy in between must not lose it (OR-5 + OR-15). */
    @Test
    void openApprovalSurvivesRestartAndCanBeDecidedOnTheNewProcess() throws Exception {
        WorkflowDefinition wf = WorkflowDefinition.builder("gated", 1)
                .stage("release", emitting("releaser", Map.of("version", "2.0.0"))).requiresApproval("production release").add()
                .stage("announce", emitting("announcer", Map.of())).dependsOn("release").add()
                .build();
        WorkflowEngine first = process(wf);
        String runId = first.start(wf, REQ, "alice");
        RunView waiting = TestAgents.awaitStage(first, runId, "release", StageStatus.AWAITING_APPROVAL);
        var approval = waiting.approvals().getFirst();
        first.shutdown();

        WorkflowEngine second = process(wf);
        second.resumeUnfinishedRuns();
        second.decide(runId, approval.approvalId(), true, "bob", approval.artifact().contentHash(), "approved after deploy");
        RunView done = second.awaitCompletion(runId, WAIT);

        assertThat(done.status()).isEqualTo(RunStatus.SUCCEEDED);
        assertThat(done.stage("release").attempts()).as("approved output reused, not regenerated").isEqualTo(1);
        assertThat(done.approvals().getFirst().decidedBy()).isEqualTo("bob");
    }

    @Test
    void finishedRunsRemainQueryableAfterRestart() throws Exception {
        WorkflowDefinition wf = WorkflowDefinition.builder("history", 1).stage("a", emitting("a", Map.of("v", 1))).add().build();
        WorkflowEngine first = process(wf);
        String runId = first.start(wf, REQ, "alice");
        RunView original = first.awaitCompletion(runId, WAIT);
        first.shutdown();

        WorkflowEngine second = process(wf);

        assertThat(second.get(runId)).isEqualTo(original);
        assertThat(second.events(runId)).hasSize((int) original.eventCount());
    }
}
