package com.agentic.orchestration.engine;

import static com.agentic.orchestration.engine.TestAgents.agent;
import static com.agentic.orchestration.engine.TestAgents.awaitStage;
import static com.agentic.orchestration.engine.TestAgents.emitting;
import static com.agentic.orchestration.engine.TestAgents.failing;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.agentic.orchestration.agent.AgentResult;
import com.agentic.orchestration.definition.Gates;
import com.agentic.orchestration.definition.WorkflowDefinition;
import com.agentic.orchestration.engine.GovernanceException.Violation;
import com.agentic.orchestration.event.FailureKind;
import com.agentic.orchestration.event.InMemoryRunEventStore;
import com.agentic.orchestration.event.RunEvent;
import com.agentic.orchestration.governance.ChangeControlPolicy;
import com.agentic.orchestration.governance.PolicyEngine;
import com.agentic.orchestration.governance.SecretLeakPolicy;
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
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/** Governance controls of the engine (OR-5 to OR-9, OR-13, OR-14, OR-16), one behaviour per test. */
class GovernanceTest {

    private static final Duration WAIT = Duration.ofSeconds(10);
    private static final Requirement REQ = new Requirement("Add click limits", "Stop after N clicks");

    private final InMemoryRunEventStore store = new InMemoryRunEventStore();
    private WorkflowEngine engine = engine(PolicyEngine.none(), Duration.ofHours(1));

    private WorkflowEngine engine(PolicyEngine policies, Duration approvalTtl) {
        return new WorkflowEngine(store, new WorkflowCatalog(List.of()), policies,
                new GovernanceSettings(Duration.ofSeconds(30), approvalTtl), JsonMapper.builder().build(), Clock.systemUTC());
    }

    @AfterEach
    void shutdown() {
        engine.shutdown();
    }

    private RunView run(WorkflowDefinition wf) throws Exception {
        return engine.awaitCompletion(engine.start(wf, REQ, "alice"), WAIT);
    }

    private List<RunEvent> events(RunView view, Class<? extends RunEvent> type) {
        return store.load(view.runId()).stream().filter(type::isInstance).toList();
    }

    private static Approval pending(RunView view) {
        return view.approvals().stream().filter(a -> a.status() == ApprovalStatus.PENDING).findFirst().orElseThrow();
    }

    @Nested
    class RetriesFallbackAndTimeouts {

        @Test
        void retriesWithExponentialBackoffUntilSuccess() throws Exception {
            AtomicInteger calls = new AtomicInteger();
            WorkflowDefinition wf = WorkflowDefinition.builder("retry", 1)
                    .stage("flaky", agent("flaky", ctx -> {
                        if (calls.incrementAndGet() < 3) {
                            throw new IllegalStateException("transient " + calls.get());
                        }
                        return AgentResult.of(Map.of("ok", true));
                    })).retry(3, Duration.ofMillis(10)).add()
                    .build();

            RunView view = run(wf);

            assertThat(view.status()).isEqualTo(RunStatus.SUCCEEDED);
            assertThat(view.stage("flaky").attempts()).isEqualTo(3);
            assertThat(events(view, RunEvent.RetryScheduled.class)).extracting(e -> ((RunEvent.RetryScheduled) e).delayMillis())
                    .containsExactly(10L, 20L);
            assertThat(events(view, RunEvent.AttemptFailed.class)).hasSize(2);
        }

        @Test
        void exitGateFailuresAreRetriedToo() throws Exception {
            AtomicInteger calls = new AtomicInteger();
            WorkflowDefinition wf = WorkflowDefinition.builder("gate-retry", 1)
                    .stage("tests", agent("tester", ctx -> AgentResult.of(Map.of("failures", calls.incrementAndGet() == 1 ? 1 : 0))))
                    .exitGate(Gates.outputField("failures", n -> n.asInt() == 0, "tests must pass"))
                    .retry(2, Duration.ofMillis(1)).add()
                    .build();

            RunView view = run(wf);

            assertThat(view.status()).isEqualTo(RunStatus.SUCCEEDED);
            assertThat(view.artifacts()).as("both outputs kept for audit").hasSize(2);
        }

        @Test
        void fallbackAgentTakesOverOnceRetriesAreExhausted() throws Exception {
            WorkflowDefinition wf = WorkflowDefinition.builder("fallback", 1)
                    .stage("design", failing("llm-designer", "model unavailable"))
                    .retry(2, Duration.ofMillis(1))
                    .fallback(emitting("template-designer", Map.of("design", "template")))
                    .add()
                    .build();

            RunView view = run(wf);

            assertThat(view.status()).isEqualTo(RunStatus.SUCCEEDED);
            RunView.StageView design = view.stage("design");
            assertThat(design.attempts()).isEqualTo(3);
            assertThat(design.fallbackActive()).isTrue();
            assertThat(design.agent()).isEqualTo("template-designer");
            assertThat(events(view, RunEvent.FallbackActivated.class)).singleElement()
                    .satisfies(e -> assertThat(((RunEvent.FallbackActivated) e).reason()).contains("after 2 attempt(s)"));
        }

        @Test
        void failingFallbackFailsTheStageWithoutFurtherAttempts() throws Exception {
            WorkflowDefinition wf = WorkflowDefinition.builder("fallback-fails", 1)
                    .stage("design", failing("primary", "down")).retry(2, Duration.ofMillis(1))
                    .fallback(failing("secondary", "also down")).add()
                    .build();

            RunView view = run(wf);

            assertThat(view.stage("design").status()).isEqualTo(StageStatus.FAILED);
            assertThat(view.stage("design").attempts()).isEqualTo(3);
            assertThat(view.stage("design").lastFailure()).contains("also down");
        }

        @Test
        void timedOutAttemptIsCancelledItsLateResultDiscardedAndTheRetrySucceeds() throws Exception {
            AtomicInteger calls = new AtomicInteger();
            AtomicBoolean firstAttemptInterrupted = new AtomicBoolean();
            WorkflowDefinition wf = WorkflowDefinition.builder("timeout", 1)
                    .stage("slow", agent("slow", ctx -> {
                        if (calls.incrementAndGet() == 1) {
                            try {
                                Thread.sleep(5_000);
                            } catch (InterruptedException e) {
                                firstAttemptInterrupted.set(true);
                                return AgentResult.of(Map.of("attempt", 1)); // late result: must be ignored
                            }
                        }
                        return AgentResult.of(Map.of("attempt", calls.get()));
                    })).timeout(Duration.ofMillis(150)).retry(2, Duration.ofMillis(1)).add()
                    .build();

            RunView view = run(wf);

            assertThat(view.status()).isEqualTo(RunStatus.SUCCEEDED);
            assertThat(firstAttemptInterrupted).isTrue();
            assertThat(view.latestArtifact("slow").orElseThrow().content().get("attempt").asInt()).isEqualTo(2);
            assertThat(view.artifacts()).as("late output of the timed-out attempt is discarded").hasSize(1);
            assertThat(((RunEvent.AttemptFailed) events(view, RunEvent.AttemptFailed.class).getFirst()).kind())
                    .isEqualTo(FailureKind.TIMEOUT);
        }
    }

    @Nested
    class Approvals {

        private WorkflowDefinition gatedRelease() {
            return WorkflowDefinition.builder("release", 1)
                    .stage("build", emitting("builder", Map.of("artifact", "app-1.2.0.jar"))).add()
                    .stage("release", emitting("releaser", Map.of("version", "1.2.0")))
                    .dependsOn("build").requiresApproval("production release").add()
                    .stage("announce", emitting("announcer", Map.of("sent", true))).dependsOn("release").add()
                    .build();
        }

        @Test
        void highImpactStageWaitsForAHumanAndDownstreamWaitsWithIt() throws Exception {
            String runId = engine.start(gatedRelease(), REQ, "alice");
            RunView waiting = awaitStage(engine, runId, "release", StageStatus.AWAITING_APPROVAL);
            assertThat(waiting.stage("announce").status()).isEqualTo(StageStatus.PENDING);

            Approval approval = pending(waiting);
            assertThat(approval.reasons()).containsExactly("high-impact stage: production release");
            assertThat(approval.artifact()).isEqualTo(waiting.latestArtifact("release").orElseThrow().ref());

            Approval decided = engine.decide(runId, approval.approvalId(), true, "bob", approval.artifact().contentHash(), "LGTM");
            RunView done = engine.awaitCompletion(runId, WAIT);

            assertThat(decided.status()).isEqualTo(ApprovalStatus.APPROVED);
            assertThat(decided.decidedBy()).isEqualTo("bob");
            assertThat(done.status()).isEqualTo(RunStatus.SUCCEEDED);
        }

        @Test
        void initiatorCannotApproveTheirOwnRun() throws Exception {
            String runId = engine.start(gatedRelease(), REQ, "alice");
            Approval approval = pending(awaitStage(engine, runId, "release", StageStatus.AWAITING_APPROVAL));

            assertThatThrownBy(() -> engine.decide(runId, approval.approvalId(), true, "alice", approval.artifact().contentHash(), ""))
                    .isInstanceOf(GovernanceException.class)
                    .extracting(e -> ((GovernanceException) e).violation()).isEqualTo(Violation.SELF_APPROVAL_FORBIDDEN);
            assertThat(engine.get(runId).stage("release").status()).isEqualTo(StageStatus.AWAITING_APPROVAL);
        }

        @Test
        void approvalOfADifferentArtifactVersionIsRefused() throws Exception {
            String runId = engine.start(gatedRelease(), REQ, "alice");
            Approval approval = pending(awaitStage(engine, runId, "release", StageStatus.AWAITING_APPROVAL));
            String reviewedSomethingElse = "0".repeat(64);

            assertThatThrownBy(() -> engine.decide(runId, approval.approvalId(), true, "bob", reviewedSomethingElse, ""))
                    .extracting(e -> ((GovernanceException) e).violation()).isEqualTo(Violation.STALE_APPROVAL);
        }

        @Test
        void aDecisionCannotBeMadeTwice() throws Exception {
            String runId = engine.start(gatedRelease(), REQ, "alice");
            Approval approval = pending(awaitStage(engine, runId, "release", StageStatus.AWAITING_APPROVAL));
            engine.decide(runId, approval.approvalId(), false, "bob", approval.artifact().contentHash(), "not today");
            engine.awaitCompletion(runId, WAIT);

            assertThatThrownBy(() -> engine.decide(runId, approval.approvalId(), true, "carol", approval.artifact().contentHash(), ""))
                    .extracting(e -> ((GovernanceException) e).violation()).isEqualTo(Violation.RUN_NOT_ACTIVE);
        }

        @Test
        void unansweredApprovalExpiresAndFailsTheStage() throws Exception {
            engine.shutdown();
            engine = engine(PolicyEngine.none(), Duration.ofMillis(150));

            RunView view = run(gatedRelease());

            assertThat(view.status()).isEqualTo(RunStatus.FAILED);
            assertThat(view.stage("release").lastFailureKind()).isEqualTo(FailureKind.APPROVAL_EXPIRED);
            assertThat(view.approvals().getFirst().status()).isEqualTo(ApprovalStatus.EXPIRED);
            assertThat(view.approvals().getFirst().decidedBy()).isEqualTo("system");
        }
    }

    @Nested
    class Rollback {

        @Test
        void rejectionRollsBackCompletedStagesInReverseOrder() throws Exception {
            List<String> undone = new CopyOnWriteArrayList<>();
            WorkflowDefinition wf = WorkflowDefinition.builder("rollback", 1)
                    .stage("branch", emitting("git", Map.of("branch", "feature/limits")))
                    .compensation(ctx -> {
                        undone.add(ctx.stageId());
                        return "deleted branch " + ctx.artifact().content().get("branch").asString();
                    }).add()
                    .stage("changeset", emitting("coder", Map.of("files", 3))).dependsOn("branch")
                    .compensation(ctx -> {
                        undone.add(ctx.stageId());
                        return "discarded draft changeset v" + ctx.artifact().version();
                    }).add()
                    .stage("merge", emitting("merger", Map.of())).dependsOn("changeset").requiresApproval("merge to main").add()
                    .build();

            String runId = engine.start(wf, REQ, "alice");
            Approval approval = pending(awaitStage(engine, runId, "merge", StageStatus.AWAITING_APPROVAL));
            engine.decide(runId, approval.approvalId(), false, "bob", approval.artifact().contentHash(), "breaks API contract");
            RunView view = engine.awaitCompletion(runId, WAIT);

            assertThat(undone).containsExactly("changeset", "branch");
            assertThat(view.status()).isEqualTo(RunStatus.FAILED);
            assertThat(view.statusReason()).contains("rolled back 2 stage(s)");
            assertThat(view.stage("merge").lastFailure()).isEqualTo("rejected by bob: breaks API contract");
            assertThat(view.stage("branch").status()).isEqualTo(StageStatus.ROLLED_BACK);
            assertThat(view.stage("branch").compensation()).isEqualTo("deleted branch feature/limits");
        }

        @Test
        void failedCompensationIsSurfacedForManualCleanup() throws Exception {
            WorkflowDefinition wf = WorkflowDefinition.builder("rollback-fails", 1)
                    .stage("reserve", emitting("r", Map.of())).compensation(ctx -> {
                        throw new IllegalStateException("registry unreachable");
                    }).add()
                    .stage("build", failing("b", "compile error")).dependsOn("reserve").add()
                    .build();

            RunView view = run(wf);

            assertThat(view.stage("reserve").status()).isEqualTo(StageStatus.ROLLBACK_FAILED);
            assertThat(view.stage("reserve").compensation()).contains("registry unreachable");
            assertThat(view.statusReason()).contains("1 compensation(s) failed, manual cleanup required");
        }
    }

    @Nested
    class PoliciesAndSafeStop {

        @Test
        void policyFindingRoutesOutputToHumanApproval() throws Exception {
            engine.shutdown();
            engine = engine(new PolicyEngine(List.of(new ChangeControlPolicy(15, 800))), Duration.ofHours(1));
            WorkflowDefinition wf = WorkflowDefinition.builder("schema", 1)
                    .stage("implement", emitting("coder", Map.of("changes", List.of(Map.of(
                            "path", "src/main/resources/db/migration/V5__click_limit.sql", "operation", "ADD", "linesChanged", 4)))))
                    .add()
                    .build();

            String runId = engine.start(wf, REQ, "alice");
            RunView waiting = awaitStage(engine, runId, "implement", StageStatus.AWAITING_APPROVAL);

            assertThat(pending(waiting).reasons()).singleElement().asString()
                    .startsWith("CHANGE_CONTROL/change-control: schema change");
            assertThat(waiting.stage("implement").policies()).extracting(RunState.PolicyRecord::outcome)
                    .containsExactly("REQUIRE_APPROVAL");
        }

        @Test
        void blockingPolicySafeStopsTheRunWithoutRollingBack() throws Exception {
            engine.shutdown();
            engine = engine(new PolicyEngine(List.of(new SecretLeakPolicy())), Duration.ofHours(1));
            CountDownLatch siblingRelease = new CountDownLatch(1);
            AtomicBoolean compensated = new AtomicBoolean();
            WorkflowDefinition wf = WorkflowDefinition.builder("leak", 1)
                    .stage("plan", emitting("planner", Map.of())).compensation(ctx -> {
                        compensated.set(true);
                        return "undone";
                    }).add()
                    .stage("config", emitting("configurer", Map.of("file", "password=hunter2hunter2"))).dependsOn("plan").add()
                    .stage("docs", agent("writer", ctx -> {
                        siblingRelease.await(5, TimeUnit.SECONDS);
                        return AgentResult.of(Map.of("ok", true));
                    })).dependsOn("plan").add()
                    .stage("release", emitting("releaser", Map.of())).dependsOn("config", "docs").add()
                    .build();

            String runId = engine.start(wf, REQ, "alice");
            awaitStage(engine, runId, "config", StageStatus.FAILED);
            siblingRelease.countDown();
            RunView view = engine.awaitCompletion(runId, WAIT);

            assertThat(view.status()).isEqualTo(RunStatus.STOPPED);
            assertThat(view.stopRequestedBy()).isEqualTo("policy-engine");
            assertThat(view.stage("config").lastFailureKind()).isEqualTo(FailureKind.POLICY_BLOCKED);
            assertThat(view.stage("config").lastFailure()).doesNotContain("hunter2");
            assertThat(view.stage("docs").status()).as("in-flight work completes").isEqualTo(StageStatus.SUCCEEDED);
            assertThat(view.stage("release").status()).isEqualTo(StageStatus.SKIPPED);
            assertThat(compensated).as("safe-stop preserves state for investigation").isFalse();
        }

        @Test
        void operatorSafeStopLetsCooperativeAgentsFinishAndWithdrawsOpenApprovals() throws Exception {
            CountDownLatch started = new CountDownLatch(1);
            WorkflowDefinition wf = WorkflowDefinition.builder("stoppable", 1)
                    .stage("plan", emitting("planner", Map.of())).add()
                    .stage("review", emitting("reviewer", Map.of())).dependsOn("plan").requiresApproval("design sign-off").add()
                    .stage("analyse", agent("analyser", ctx -> {
                        started.countDown();
                        while (!ctx.isCancelled()) {
                            Thread.sleep(5);
                        }
                        return AgentResult.of(Map.of("partial", true));
                    })).dependsOn("plan").add()
                    .stage("ship", emitting("shipper", Map.of())).dependsOn("review", "analyse").add()
                    .build();

            String runId = engine.start(wf, REQ, "alice");
            started.await();
            awaitStage(engine, runId, "review", StageStatus.AWAITING_APPROVAL);
            engine.requestStop(runId, "admin", "requirement withdrawn by product owner");
            RunView view = engine.awaitCompletion(runId, WAIT);

            assertThat(view.status()).isEqualTo(RunStatus.STOPPED);
            assertThat(view.statusReason()).isEqualTo("safe-stop: requirement withdrawn by product owner");
            assertThat(view.stage("analyse").status()).isEqualTo(StageStatus.SUCCEEDED);
            assertThat(view.stage("review").status()).isEqualTo(StageStatus.SKIPPED);
            assertThat(view.approvals().getFirst().status()).isEqualTo(ApprovalStatus.WITHDRAWN);
            assertThat(view.stage("ship").status()).isEqualTo(StageStatus.SKIPPED);
        }
    }

    @Test
    void complexGovernedRunReplaysToIdenticalState() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        WorkflowDefinition wf = WorkflowDefinition.builder("replay-governed", 1)
                .stage("a", agent("a", ctx -> {
                    if (calls.incrementAndGet() == 1) {
                        throw new IllegalStateException("flaky");
                    }
                    return AgentResult.of(Map.of("v", 1)).withDecision("chose v1", "simplest");
                })).retry(2, Duration.ofMillis(1)).compensation(ctx -> "undo a").add()
                .stage("b", emitting("b", Map.of())).dependsOn("a").requiresApproval("check").add()
                .build();

        String runId = engine.start(wf, REQ, "alice");
        Approval approval = pending(awaitStage(engine, runId, "b", StageStatus.AWAITING_APPROVAL));
        engine.decide(runId, approval.approvalId(), false, "carol", approval.artifact().contentHash(), "no");
        RunView live = engine.awaitCompletion(runId, WAIT);

        assertThat(RunState.replay(store.load(runId)).view()).isEqualTo(live);
        assertThat(live.stage("a").status()).isEqualTo(StageStatus.ROLLED_BACK);
    }
}
