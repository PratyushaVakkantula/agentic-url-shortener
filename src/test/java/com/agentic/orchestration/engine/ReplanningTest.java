package com.agentic.orchestration.engine;

import static com.agentic.orchestration.engine.TestAgents.agent;
import static com.agentic.orchestration.engine.TestAgents.awaitStage;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.agentic.orchestration.agent.AgentResult;
import com.agentic.orchestration.definition.WorkflowDefinition;
import com.agentic.orchestration.engine.GovernanceException.Violation;
import com.agentic.orchestration.event.InMemoryRunEventStore;
import com.agentic.orchestration.event.RunEvent;
import com.agentic.orchestration.governance.ChangeControlPolicy;
import com.agentic.orchestration.governance.PolicyEngine;
import com.agentic.orchestration.governance.SecretLeakPolicy;
import com.agentic.orchestration.metrics.ReliabilityMetrics;
import com.agentic.orchestration.model.Approval;
import com.agentic.orchestration.model.ApprovalStatus;
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
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Dynamic re-planning when upstream outputs change (OR-12). */
class ReplanningTest {

    private static final Duration WAIT = Duration.ofSeconds(10);
    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    private final InMemoryRunEventStore store = new InMemoryRunEventStore();
    private final WorkflowEngine engine = new WorkflowEngine(store, new WorkflowCatalog(List.of()),
            new PolicyEngine(List.of(new SecretLeakPolicy(), new ChangeControlPolicy(15, 800))),
            new GovernanceSettings(Duration.ofSeconds(30), Duration.ofHours(1)), MAPPER, Clock.systemUTC());

    private final AtomicInteger designRuns = new AtomicInteger();
    private final AtomicInteger implementRuns = new AtomicInteger();
    private final AtomicInteger docsRuns = new AtomicInteger();

    @AfterEach
    void shutdown() {
        engine.shutdown();
    }

    /**
     * requirements ─▶ design ─▶ implement ─▶ release (needs approval)
     *       └──────▶ docs ──────────────────────┘
     * design normalises the requirement (ignores the free-text "notes"); docs reads nothing upstream.
     */
    private WorkflowDefinition sdlc() {
        return WorkflowDefinition.builder("sdlc", 1)
                .stage("requirements", agent("analyst", ctx -> AgentResult.of(Map.of(
                        "limit", "per link", "notes", "initial")))).add()
                .stage("design", agent("architect", ctx -> {
                    designRuns.incrementAndGet();
                    String limit = ctx.artifact("requirements").content().get("limit").asString();
                    return AgentResult.of(Map.of("table", "short_link", "column", "max_clicks", "scope", limit));
                })).dependsOn("requirements").add()
                .stage("implement", agent("coder", ctx -> {
                    implementRuns.incrementAndGet();
                    JsonNode design = ctx.artifact("design").content();
                    return AgentResult.of(Map.of("summary", "add " + design.get("column").asString()
                            + " scoped " + design.get("scope").asString()));
                })).dependsOn("design").add()
                .stage("docs", agent("writer", ctx -> {
                    docsRuns.incrementAndGet();
                    return AgentResult.of(Map.of("readme", "updated"));
                })).dependsOn("requirements").add()
                .stage("release", agent("releaser", ctx -> AgentResult.of(Map.of(
                        "notes", ctx.artifact("implement").content().get("summary").asString()))))
                        .dependsOn("implement", "docs").requiresApproval("production release").add()
                .build();
    }

    private static JsonNode json(Map<String, Object> content) {
        return MAPPER.valueToTree(content);
    }

    private static Approval pending(RunView view) {
        return view.approvals().stream().filter(a -> a.status() == ApprovalStatus.PENDING).findFirst().orElseThrow();
    }

    /**
     * Waits until no new events arrive for a while. Asserting as soon as one stage changes status
     * can race with the coordinator still processing the consequences of that change.
     */
    private RunView awaitQuiescent(String runId) throws InterruptedException {
        long lastCount = -1;
        long stableSince = System.nanoTime();
        long deadline = System.nanoTime() + WAIT.toNanos();
        while (System.nanoTime() < deadline) {
            long count = store.load(runId).size();
            if (count != lastCount) {
                lastCount = count;
                stableSince = System.nanoTime();
            } else if (System.nanoTime() - stableSince > Duration.ofMillis(300).toNanos()) {
                return engine.get(runId);
            }
            Thread.sleep(10);
        }
        throw new AssertionError("run never became quiescent");
    }

    @Test
    void revisionReRunsOnlyStagesThatConsumedTheChangedOutputAndVoidsTheirApproval() throws Exception {
        String runId = engine.start(sdlc(), new Requirement("Click limits", "..."), "alice");
        Approval first = pending(awaitStage(engine, runId, "release", StageStatus.AWAITING_APPROVAL));

        engine.revise(runId, "requirements", json(Map.of("limit", "per user", "notes", "initial")),
                "alice", "product owner: limit is per user");
        RunView view = awaitQuiescent(runId);
        Approval second = pending(view);

        assertThat(view.approvals()).filteredOn(a -> a.approvalId().equals(first.approvalId())).singleElement()
                .satisfies(a -> assertThat(a.status()).isEqualTo(ApprovalStatus.WITHDRAWN));
        assertThat(second.approvalId()).isNotEqualTo(first.approvalId());
        assertThat(second.artifact().contentHash()).isNotEqualTo(first.artifact().contentHash());
        assertThat(view.latestArtifact("release").orElseThrow().content().get("notes").asString())
                .isEqualTo("add max_clicks scoped per user");
        assertThat(designRuns.get()).isEqualTo(2);
        assertThat(implementRuns.get()).isEqualTo(2);
        assertThat(docsRuns.get()).as("docs never read requirements, so it is not re-run").isEqualTo(1);
        assertThat(view.stage("docs").invalidations()).isZero();
        assertThat(view.stage("design").invalidations()).isEqualTo(1);

        engine.decide(runId, second.approvalId(), true, "bob", second.artifact().contentHash(), "per-user scope OK");
        assertThat(engine.awaitCompletion(runId, WAIT).status()).isEqualTo(RunStatus.SUCCEEDED);
    }

    @Test
    void reRunThatReproducesIdenticalOutputStopsTheCascade() throws Exception {
        String runId = engine.start(sdlc(), new Requirement("Click limits", "..."), "alice");
        Approval approval = pending(awaitStage(engine, runId, "release", StageStatus.AWAITING_APPROVAL));

        // Only the free-text notes change; design ignores them, so its re-run output is identical.
        engine.revise(runId, "requirements", json(Map.of("limit", "per link", "notes", "typo fixed")), "alice", "typo");
        RunView view = awaitQuiescent(runId);

        assertThat(designRuns.get()).isEqualTo(2);
        assertThat(implementRuns.get()).as("design output unchanged (same hash), so implement is still valid").isEqualTo(1);
        assertThat(pending(view).approvalId()).as("release approval still valid").isEqualTo(approval.approvalId());
        assertThat(ReliabilityMetrics.compute(List.of(store.load(runId))).replanning().earlyCutoffs()).isEqualTo(1);
    }

    @Test
    void outputComputedFromAnInputRevisedMidFlightIsDiscardedAndRecomputed() throws Exception {
        CountDownLatch designRunning = new CountDownLatch(1);
        CountDownLatch releaseDesign = new CountDownLatch(1);
        AtomicInteger calls = new AtomicInteger();
        WorkflowDefinition wf = WorkflowDefinition.builder("midflight", 1)
                .stage("requirements", agent("analyst", ctx -> AgentResult.of(Map.of("limit", "per link")))).add()
                .stage("gate", agent("noop", ctx -> AgentResult.of(Map.of()))).dependsOn("requirements").add()
                .stage("design", agent("architect", ctx -> {
                    String limit = ctx.artifact("requirements").content().get("limit").asString();
                    if (calls.incrementAndGet() == 1) {
                        designRunning.countDown();
                        releaseDesign.await(5, TimeUnit.SECONDS);
                    }
                    return AgentResult.of(Map.of("scope", limit));
                })).dependsOn("gate").add()
                .build();

        String runId = engine.start(wf, new Requirement("x", "y"), "alice");
        designRunning.await();
        engine.revise(runId, "requirements", json(Map.of("limit", "per user")), "alice", "changed mid-flight");
        releaseDesign.countDown();
        RunView view = engine.awaitCompletion(runId, WAIT);

        assertThat(view.status()).isEqualTo(RunStatus.SUCCEEDED);
        assertThat(view.latestArtifact("design").orElseThrow().content().get("scope").asString()).isEqualTo("per user");
        assertThat(view.stage("design").attempts()).isEqualTo(2);
        assertThat(store.load(runId)).as("re-planning is not a failure")
                .noneMatch(RunEvent.AttemptFailed.class::isInstance);
        // The attempt computed from the outdated input is discarded, never accepted (not even briefly):
        // the audit trail must not claim a stale output succeeded, and nobody may be asked to approve it.
        assertThat(store.load(runId)).filteredOn(RunEvent.StageSucceeded.class::isInstance)
                .map(e -> (RunEvent.StageSucceeded) e)
                .filteredOn(e -> e.stageId().equals("design"))
                .singleElement()
                .satisfies(e -> assertThat(e.attempt()).isEqualTo(2));
    }

    @Test
    void revisionsPassTheSameGuardrailsAsAgentOutput() throws Exception {
        String runId = engine.start(sdlc(), new Requirement("Click limits", "..."), "alice");
        awaitStage(engine, runId, "release", StageStatus.AWAITING_APPROVAL);
        long eventsBefore = store.load(runId).size();

        assertThatThrownBy(() -> engine.revise(runId, "requirements",
                json(Map.of("limit", "per link", "notes", "db password=Sup3rS3cret!!")), "alice", "oops"))
                .isInstanceOf(GovernanceException.class)
                .extracting(e -> ((GovernanceException) e).violation()).isEqualTo(Violation.REVISION_BLOCKED_BY_POLICY);
        assertThat(store.load(runId)).as("blocked revision changes nothing").hasSize((int) eventsBefore);

        engine.revise(runId, "docs", json(Map.of("changes", List.of(Map.of(
                "path", "src/main/resources/db/migration/V9__x.sql", "operation", "ADD", "linesChanged", 3)))),
                "alice", "docs now include a migration");
        RunView view = awaitStage(engine, runId, "docs", StageStatus.AWAITING_APPROVAL);
        assertThat(view.approvals()).anySatisfy(a -> assertThat(a.reasons()).anyMatch(r -> r.contains("schema change")));
    }

    @Test
    void onlyAcceptedStagesOfActiveRunsCanBeRevised() throws Exception {
        String runId = engine.start(sdlc(), new Requirement("Click limits", "..."), "alice");
        awaitStage(engine, runId, "release", StageStatus.AWAITING_APPROVAL);

        assertThatThrownBy(() -> engine.revise(runId, "release", json(Map.of()), "alice", "skip review"))
                .extracting(e -> ((GovernanceException) e).violation()).isEqualTo(Violation.STAGE_NOT_REVISABLE);
        assertThatThrownBy(() -> engine.revise(runId, "nope", json(Map.of()), "alice", "?"))
                .extracting(e -> ((GovernanceException) e).violation()).isEqualTo(Violation.STAGE_NOT_REVISABLE);
    }

    @Test
    void replanningHistoryReplaysToIdenticalState() throws Exception {
        String runId = engine.start(sdlc(), new Requirement("Click limits", "..."), "alice");
        Approval first = pending(awaitStage(engine, runId, "release", StageStatus.AWAITING_APPROVAL));
        engine.revise(runId, "requirements", json(Map.of("limit", "per user", "notes", "x")), "alice", "change");
        Approval second = pending(awaitQuiescent(runId));
        assertThat(second.approvalId()).isNotEqualTo(first.approvalId());
        engine.decide(runId, second.approvalId(), false, "bob", second.artifact().contentHash(), "no");
        RunView live = engine.awaitCompletion(runId, WAIT);

        assertThat(RunState.replay(store.load(runId)).view()).isEqualTo(live);
    }
}
