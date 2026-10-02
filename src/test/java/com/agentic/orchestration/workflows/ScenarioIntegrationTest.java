package com.agentic.orchestration.workflows;

import static org.assertj.core.api.Assertions.assertThat;

import com.agentic.orchestration.engine.WorkflowEngine;
import com.agentic.orchestration.event.RunEvent;
import com.agentic.orchestration.metrics.ReliabilityMetrics;
import com.agentic.orchestration.metrics.ReliabilityReport;
import com.agentic.orchestration.model.Approval;
import com.agentic.orchestration.model.ApprovalStatus;
import com.agentic.orchestration.model.Requirement;
import com.agentic.orchestration.model.RunStatus;
import com.agentic.orchestration.model.StageStatus;
import com.agentic.orchestration.state.RunView;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The three scenarios from the brief, end to end: real agents, real policies, real database,
 * a human approver. Each test reads like the scenario walkthrough in docs/scenarios.md.
 */
@SpringBootTest
@ActiveProfiles("test")
class ScenarioIntegrationTest {

    private static final Duration WAIT = Duration.ofSeconds(20);

    @Autowired
    WorkflowEngine engine;

    @Autowired
    JsonMapper mapper;

    /** What the approver saw: which stage asked, and why. */
    record Checkpoint(String stageId, List<String> reasons) {
    }

    /** Plays the human approver (bob) until the run finishes; returns every checkpoint encountered. */
    private List<Checkpoint> approveEverything(String runId) throws Exception {
        List<Checkpoint> seen = new ArrayList<>();
        Set<String> decided = new HashSet<>();
        long deadline = System.nanoTime() + WAIT.toNanos();
        while (System.nanoTime() < deadline) {
            RunView view = engine.get(runId);
            if (view.status().isTerminal()) {
                return seen;
            }
            for (Approval approval : view.approvals()) {
                if (approval.status() == ApprovalStatus.PENDING && decided.add(approval.approvalId())) {
                    seen.add(new Checkpoint(approval.stageId(), approval.reasons()));
                    engine.decide(runId, approval.approvalId(), true, "bob", approval.artifact().contentHash(), "reviewed");
                }
            }
            Thread.sleep(10);
        }
        throw new AssertionError("run " + runId + " did not finish; status " + engine.get(runId).status());
    }

    private Approval awaitPending(String runId, String stageId, Set<String> ignore) throws InterruptedException {
        long deadline = System.nanoTime() + WAIT.toNanos();
        while (System.nanoTime() < deadline) {
            var pending = engine.get(runId).approvals().stream()
                    .filter(a -> a.status() == ApprovalStatus.PENDING && a.stageId().equals(stageId) && !ignore.contains(a.approvalId()))
                    .findFirst();
            if (pending.isPresent()) {
                return pending.get();
            }
            Thread.sleep(10);
        }
        throw new AssertionError("no pending approval for " + stageId);
    }

    private JsonNode artifact(RunView view, String stage) {
        return view.latestArtifact(stage).orElseThrow().content();
    }

    @Test
    void greenfield_newCapabilityFlowsThroughEveryStageWithTwoHumanCheckpoints() throws Exception {
        String runId = engine.start(SdlcWorkflows.GREENFIELD, new Requirement("QR codes for short links",
                "Users must be able to download a QR code for any short link. The QR image must be a PNG of 300x300 pixels. "
                        + "Requesting a QR code for an unknown link must return 404."), "alice");

        List<Checkpoint> checkpoints = approveEverything(runId);
        RunView view = engine.get(runId);

        assertThat(view.status()).isEqualTo(RunStatus.SUCCEEDED);
        assertThat(view.stages()).allMatch(s -> s.status() == StageStatus.SUCCEEDED);
        assertThat(checkpoints).extracting(Checkpoint::stageId).containsExactly("implementation", "release");
        assertThat(checkpoints.getFirst().reasons()).anyMatch(r -> r.contains("schema change"));

        JsonNode requirements = artifact(view, "requirements");
        assertThat(requirements.get("classification").asString()).isEqualTo("WELL_DEFINED");
        assertThat(requirements.get("acceptanceCriteria")).hasSize(3);
        assertThat(artifact(view, "test-plan").get("uncoveredCriteria")).isEmpty();
        assertThat(artifact(view, "release").get("bump").asString()).isEqualTo("MINOR");
        assertThat(artifact(view, "implementation").get("tasks").size()).isGreaterThanOrEqualTo(5);

        // test-plan and security-review ran in parallel after implementation, then joined at docs
        List<RunEvent> events = engine.events(runId);
        long docsStarted = seqOf(events, "docs");
        assertThat(docsStarted).isGreaterThan(succeededSeq(events, "test-plan")).isGreaterThan(succeededSeq(events, "security-review"));
    }

    @Test
    void brownfield_staticAnalysisOfThisRepositoryDrivesDesignAndChangeControl() throws Exception {
        String runId = engine.start(SdlcWorkflows.BROWNFIELD, new Requirement("Add per-link click limits",
                "Each short link can have an optional maximum number of clicks. Once the limit is reached, the link must stop "
                        + "redirecting and return 410 Gone. The limit must be set when the link is created."), "alice");

        List<Checkpoint> checkpoints = approveEverything(runId);
        RunView view = engine.get(runId);

        assertThat(view.status()).isEqualTo(RunStatus.SUCCEEDED);
        JsonNode impact = artifact(view, "impact-analysis");
        assertThat(impact.get("primaryModules").get(0).asString()).isEqualTo("shortener");
        assertThat(impact.get("tables").get(0).asString()).isEqualTo("short_link");
        assertThat(impact.get("stats").get("typesScanned").asInt()).isGreaterThan(50);

        JsonNode design = artifact(view, "design");
        String migration = design.get("schemaChanges").get(0).get("migration").asString();
        assertThat(migration).startsWith(impact.get("nextMigration").asString() + "__add_").endsWith("_to_short_link.sql");
        assertThat(design.get("apiChanges").toString()).contains("POST /api/v1/urls").contains("410");

        assertThat(checkpoints).extracting(Checkpoint::stageId).containsExactly("implementation", "release");
        assertThat(checkpoints.getFirst().reasons()).singleElement().asString().contains("schema change").contains(migration);
        assertThat(artifact(view, "test-plan").get("regressionSuites").toString()).contains("ShortenerApiIntegrationTest");
        assertThat(artifact(view, "security-review").get("highestSeverity").asString()).isEqualTo("MEDIUM");
    }

    /**
     * The vague requirement stops at a clarification checkpoint. Instead of approving the agent's
     * assumptions, the product owner corrects the requirements artifact; re-planning re-runs the
     * clarification stage (its first approval is withdrawn) and everything downstream works from
     * the precise version.
     */
    @Test
    void ambiguous_clarifiedByAHumanRevisionThatReplansTheRun() throws Exception {
        String runId = engine.start(SdlcWorkflows.AMBIGUOUS, new Requirement("Make the URL shortener faster and more reliable", ""), "alice");

        Approval first = awaitPending(runId, "clarification", Set.of());
        RunView waiting = engine.get(runId);
        assertThat(artifact(waiting, "requirements").get("classification").asString()).isEqualTo("AMBIGUOUS");
        assertThat(artifact(waiting, "clarification").get("openQuestions").toString()).contains("faster").contains("reliable");
        assertThat(waiting.stage("impact-analysis").status()).as("nothing is built on unconfirmed assumptions").isEqualTo(StageStatus.PENDING);

        JsonNode clarified = mapper.valueToTree(Map.of(
                "title", "Faster and more reliable redirects",
                "statement", "Redirect p95 latency must be below 50 ms at 200 requests/s. Redirects must keep working when analytics storage is unavailable.",
                "classification", "WELL_DEFINED", "clarityScore", 1.0, "needsClarification", false,
                "acceptanceCriteria", List.of(
                        Map.of("id", "AC-1", "text", "Redirect p95 latency must be below 50 ms at 200 requests/s", "measurable", true),
                        Map.of("id", "AC-2", "text", "Redirects must keep working when analytics storage is unavailable", "measurable", true)),
                "ambiguities", List.of(), "assumptions", List.of(),
                "keywords", List.of("redirect", "latency", "click", "analytic", "storage")));
        engine.revise(runId, "requirements", clarified, "alice", "product owner set concrete targets");

        Approval second = awaitPending(runId, "clarification", Set.of(first.approvalId()));
        assertThat(second.artifact().contentHash()).isNotEqualTo(first.artifact().contentHash());
        List<Checkpoint> rest = approveEverything(runId);
        RunView view = engine.get(runId);

        assertThat(view.status()).isEqualTo(RunStatus.SUCCEEDED);
        assertThat(view.approvals()).filteredOn(a -> a.approvalId().equals(first.approvalId())).singleElement()
                .satisfies(a -> assertThat(a.status()).isEqualTo(ApprovalStatus.WITHDRAWN));
        assertThat(rest).extracting(Checkpoint::stageId).contains("clarification", "release");
        assertThat(artifact(view, "clarification").get("openQuestions")).isEmpty();
        assertThat(artifact(view, "design").get("basedOnAssumptions")).as("design built on confirmed facts").isEmpty();
        assertThat(artifact(view, "impact-analysis").get("primaryModules").get(0).asString()).isEqualTo("shortener");

        ReliabilityReport metrics = ReliabilityMetrics.compute(List.of(engine.events(runId)));
        assertThat(metrics.replanning().revisions()).isEqualTo(1);
        assertThat(metrics.replanning().invalidations()).isGreaterThanOrEqualTo(1);
        assertThat(metrics.governance().withdrawn()).isGreaterThanOrEqualTo(1);
    }

    private static long seqOf(List<RunEvent> events, String stageId) {
        return events.stream().filter(e -> e instanceof RunEvent.StageStarted s && s.stageId().equals(stageId))
                .mapToLong(RunEvent::seq).findFirst().orElseThrow();
    }

    private static long succeededSeq(List<RunEvent> events, String stageId) {
        return events.stream().filter(e -> e instanceof RunEvent.StageSucceeded s && s.stageId().equals(stageId))
                .mapToLong(RunEvent::seq).findFirst().orElseThrow();
    }
}
