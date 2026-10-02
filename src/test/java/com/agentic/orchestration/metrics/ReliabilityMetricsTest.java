package com.agentic.orchestration.metrics;

import static org.assertj.core.api.Assertions.assertThat;

import com.agentic.orchestration.event.FailureKind;
import com.agentic.orchestration.event.RunEvent;
import com.agentic.orchestration.governance.PolicyCategory;
import com.agentic.orchestration.governance.PolicyOutcome;
import com.agentic.orchestration.model.ApprovalStatus;
import com.agentic.orchestration.model.ArtifactRef;
import com.agentic.orchestration.model.Requirement;
import com.agentic.orchestration.model.RunStatus;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Metric definitions checked against hand-built event logs with known timestamps, so each number
 * can be verified with pencil and paper.
 */
class ReliabilityMetricsTest {

    private static final Instant T0 = Instant.parse("2026-05-01T10:00:00Z");

    /** Builds one run's event log; seconds are offsets from T0. */
    private static final class Log {
        final String runId;
        final List<RunEvent> events = new ArrayList<>();

        Log(String runId) {
            this.runId = runId;
            events.add(new RunEvent.RunStarted(runId, 1, T0, "wf", 1, new Requirement("t", "d"), "alice", List.of("a", "b")));
        }

        private long seq() {
            return events.size() + 1L;
        }

        private static Instant at(int s) {
            return T0.plusSeconds(s);
        }

        Log started(int s, String stage, int attempt) {
            events.add(new RunEvent.StageStarted(runId, seq(), at(s), stage, attempt, "agent"));
            return this;
        }

        Log attemptFailed(int s, String stage, int attempt, FailureKind kind) {
            events.add(new RunEvent.AttemptFailed(runId, seq(), at(s), stage, attempt, kind, "x"));
            events.add(new RunEvent.RetryScheduled(runId, seq(), at(s), stage, attempt + 1, 0));
            return this;
        }

        Log succeeded(int s, String stage, int attempt) {
            events.add(new RunEvent.StageSucceeded(runId, seq(), at(s), stage, attempt, 0));
            return this;
        }

        Log failed(int s, String stage) {
            events.add(new RunEvent.StageFailed(runId, seq(), at(s), stage, 1, FailureKind.AGENT_ERROR, "x"));
            return this;
        }

        Log approval(int requested, int decided, ApprovalStatus decision) {
            String id = "ap-" + requested;
            events.add(new RunEvent.ApprovalRequested(runId, seq(), at(requested), id, "b", 1,
                    new ArtifactRef("b", 1, "h"), List.of("r"), at(requested + 3600)));
            events.add(new RunEvent.ApprovalDecided(runId, seq(), at(decided), id, "b", decision, "bob", ""));
            return this;
        }

        Log policy(int s, PolicyOutcome outcome) {
            events.add(new RunEvent.PolicyEvaluated(runId, seq(), at(s), "b", 1, "p", PolicyCategory.SECURITY, outcome, "r"));
            return this;
        }

        Log rollback(int s) {
            events.add(new RunEvent.RollbackStarted(runId, seq(), at(s), List.of("a")));
            events.add(new RunEvent.StageCompensated(runId, seq(), at(s), "a", false, "boom"));
            return this;
        }

        Log completed(int s, RunStatus status) {
            events.add(new RunEvent.RunCompleted(runId, seq(), at(s), status, "done"));
            return this;
        }
    }

    @Test
    void computesEveryMetricExactlyFromTheLog() {
        // run 1: a retried twice (fails at 10s, recovers at 70s), b approved after 120s. 200s total.
        Log run1 = new Log("r1")
                .started(0, "a", 1).attemptFailed(10, "a", 1, FailureKind.TIMEOUT)
                .started(20, "a", 2).attemptFailed(30, "a", 2, FailureKind.AGENT_ERROR)
                .started(40, "a", 3).succeeded(70, "a", 3)
                .started(80, "b", 1).policy(90, PolicyOutcome.REQUIRE_APPROVAL).approval(80, 200, ApprovalStatus.APPROVED)
                .succeeded(200, "b", 1).completed(200, RunStatus.SUCCEEDED);
        // run 2: a first-pass success, b fails, rollback with a failed compensation. 50s total.
        Log run2 = new Log("r2")
                .started(0, "a", 1).succeeded(20, "a", 1)
                .started(20, "b", 1).failed(40, "b").rollback(45).completed(50, RunStatus.FAILED);
        // run 3: policy block → safe-stop. 30s total.
        Log run3 = new Log("r3")
                .started(0, "a", 1).policy(25, PolicyOutcome.BLOCK).failed(25, "a").completed(30, RunStatus.STOPPED);
        // run 4: still running; counted in total only.
        Log run4 = new Log("r4").started(0, "a", 1);

        ReliabilityReport r = ReliabilityMetrics.compute(List.of(run1.events, run2.events, run3.events, run4.events));

        assertThat(r.runs()).isEqualTo(new ReliabilityReport.Runs(4, 1, 1, 1, 1, 0.333));
        assertThat(r.stages().attempts()).isEqualTo(8);
        assertThat(r.stages().retries()).isEqualTo(2);
        assertThat(r.stages().retryRate()).isEqualTo(0.25);
        assertThat(r.stages().timeouts()).isEqualTo(1);
        assertThat(r.stages().firstPassYield()).as("b(r1), a(r2) first pass; a(r1) not").isEqualTo(0.667);
        assertThat(r.recovery().recoveredFailures()).isEqualTo(1);
        assertThat(r.recovery().mttrMillis()).as("first failure 10s → success 70s").isEqualTo(60_000L);
        assertThat(r.recovery().unrecoveredFailures()).isEqualTo(2);
        assertThat(r.recovery().rollbacks()).isEqualTo(1);
        assertThat(r.recovery().rollbackFrequency()).isEqualTo(0.333);
        assertThat(r.recovery().compensationFailures()).isEqualTo(1);
        assertThat(r.latency().p50Millis()).as("sorted 30s, 50s, 200s").isEqualTo(50_000L);
        assertThat(r.latency().p95Millis()).isEqualTo(200_000L);
        assertThat(r.latency().approvalWaitP50Millis()).isEqualTo(120_000L);
        assertThat(r.governance().approved()).isEqualTo(1);
        assertThat(r.governance().policyBlocks()).isEqualTo(1);
        assertThat(r.governance().policyEscalations()).isEqualTo(1);
        assertThat(r.governance().humanInterventionRate()).as("only run 1 had a human decision").isEqualTo(0.333);
    }

    @Test
    void emptyInputGivesZeroRatesAndNullLatenciesNotMisleadingZeros() {
        ReliabilityReport r = ReliabilityMetrics.compute(List.of());
        assertThat(r.runs().successRate()).isZero();
        assertThat(r.latency().p50Millis()).isNull();
        assertThat(r.recovery().mttrMillis()).isNull();
    }
}
