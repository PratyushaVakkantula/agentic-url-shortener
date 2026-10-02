package com.agentic.orchestration.metrics;

import com.agentic.orchestration.event.FailureKind;
import com.agentic.orchestration.event.RunEvent;
import com.agentic.orchestration.governance.PolicyOutcome;
import com.agentic.orchestration.model.ApprovalStatus;
import com.agentic.orchestration.model.RunStatus;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Computes {@link ReliabilityReport} from event logs. A pure function: the same events always
 * give the same numbers, and every metric can be re-derived from the audit trail by hand.
 * That is the advantage of computing from the log rather than sampling counters.
 *
 * <p>Cost is O(total events). Fine for this scale; at volume, maintain these as a projection
 * updated on append (the same code applied incrementally).
 */
public final class ReliabilityMetrics {

    private ReliabilityMetrics() {
    }

    public static ReliabilityReport compute(Collection<List<RunEvent>> runs) {
        Acc acc = new Acc();
        runs.forEach(acc::run);
        return acc.report();
    }

    private static final class Acc {
        long total, running, succeeded, failed, stopped;
        long attempts, retries, fallbacks, timeouts, stagesSucceeded, firstPass;
        long rollbacks, compensationFailures, recovered, unrecovered;
        long approvalsRequested, approved, rejected, expired, withdrawn, policyBlocks, policyEscalations, safeStops;
        long humanTouchedRuns;
        long revisions, invalidations, earlyCutoffs;
        final List<Long> runLatencies = new ArrayList<>();
        final List<Long> approvalWaits = new ArrayList<>();
        final List<Long> recoveryTimes = new ArrayList<>();

        void run(List<RunEvent> events) {
            if (events.isEmpty()) {
                return;
            }
            total++;
            Instant started = events.getFirst().at();
            Map<String, Instant> firstFailure = new HashMap<>();   // stage → first failed attempt in this generation
            Map<String, Integer> attemptsPerStage = new HashMap<>();
            Map<String, String> lastHash = new HashMap<>();
            Set<String> invalidatedStages = new HashSet<>();
            Map<String, Instant> approvalRequestedAt = new HashMap<>();
            boolean human = false;
            boolean finished = false;

            for (RunEvent event : events) {
                switch (event) {
                    case RunEvent.StageStarted e -> {
                        attempts++;
                        attemptsPerStage.merge(e.stageId(), 1, Integer::sum);
                    }
                    case RunEvent.AttemptFailed e -> {
                        firstFailure.putIfAbsent(e.stageId(), e.at());
                        if (e.kind() == FailureKind.TIMEOUT) {
                            timeouts++;
                        }
                    }
                    case RunEvent.RetryScheduled e -> retries++;
                    case RunEvent.FallbackActivated e -> fallbacks++;
                    case RunEvent.StageSucceeded e -> {
                        stagesSucceeded++;
                        if (attemptsPerStage.getOrDefault(e.stageId(), 0) <= 1) {
                            firstPass++;
                        }
                        Instant failedAt = firstFailure.remove(e.stageId());
                        if (failedAt != null) {
                            recovered++;
                            recoveryTimes.add(Duration.between(failedAt, e.at()).toMillis());
                        }
                    }
                    case RunEvent.StageFailed e -> {
                        firstFailure.remove(e.stageId());
                        unrecovered++;
                        if (e.kind() == FailureKind.TIMEOUT) {
                            timeouts++;
                        }
                    }
                    case RunEvent.PolicyEvaluated e -> {
                        if (e.outcome() == PolicyOutcome.BLOCK) {
                            policyBlocks++;
                        } else if (e.outcome() == PolicyOutcome.REQUIRE_APPROVAL) {
                            policyEscalations++;
                        }
                    }
                    case RunEvent.ApprovalRequested e -> {
                        approvalsRequested++;
                        approvalRequestedAt.put(e.approvalId(), e.at());
                    }
                    case RunEvent.ApprovalDecided e -> {
                        switch (e.decision()) {
                            case APPROVED -> approved++;
                            case REJECTED -> rejected++;
                            case EXPIRED -> expired++;
                            case WITHDRAWN -> withdrawn++;
                            case PENDING -> { }
                        }
                        if (e.decision() == ApprovalStatus.APPROVED || e.decision() == ApprovalStatus.REJECTED) {
                            human = true;
                            Instant requested = approvalRequestedAt.get(e.approvalId());
                            if (requested != null) {
                                approvalWaits.add(Duration.between(requested, e.at()).toMillis());
                            }
                        }
                    }
                    case RunEvent.StopRequested e -> {
                        safeStops++;
                        human = human || !"policy-engine".equals(e.actor());
                    }
                    case RunEvent.RollbackStarted e -> rollbacks++;
                    case RunEvent.StageCompensated e -> {
                        if (!e.succeeded()) {
                            compensationFailures++;
                        }
                    }
                    case RunEvent.ArtifactRevised e -> {
                        revisions++;
                        human = true;
                        lastHash.put(e.artifact().stageId(), e.artifact().contentHash());
                    }
                    case RunEvent.StageInvalidated e -> {
                        invalidations++;
                        invalidatedStages.add(e.stageId());
                    }
                    case RunEvent.ArtifactProduced e -> {
                        String stage = e.artifact().stageId();
                        String previous = lastHash.put(stage, e.artifact().contentHash());
                        if (invalidatedStages.remove(stage) && e.artifact().contentHash().equals(previous)) {
                            earlyCutoffs++;
                        }
                    }
                    case RunEvent.RunCompleted e -> {
                        finished = true;
                        runLatencies.add(Duration.between(started, e.at()).toMillis());
                        switch (e.status()) {
                            case SUCCEEDED -> succeeded++;
                            case FAILED -> failed++;
                            case STOPPED -> stopped++;
                            case RUNNING -> { }
                        }
                    }
                    case RunEvent.RunStarted e -> { }
                    case RunEvent.GateEvaluated e -> { }
                    case RunEvent.DecisionRecorded e -> { }
                    case RunEvent.RunResumed e -> { }
                    case RunEvent.StageSkipped e -> { }
                }
            }
            if (!finished) {
                running++;
            } else if (human) {
                humanTouchedRuns++;
            }
        }

        ReliabilityReport report() {
            long finishedRuns = succeeded + failed + stopped;
            return new ReliabilityReport(
                    new ReliabilityReport.Runs(total, running, succeeded, failed, stopped, ratio(succeeded, finishedRuns)),
                    new ReliabilityReport.Stages(attempts, retries, fallbacks, timeouts, ratio(retries, attempts),
                            ratio(timeouts, attempts), ratio(firstPass, stagesSucceeded)),
                    new ReliabilityReport.Recovery(rollbacks, ratio(rollbacks, finishedRuns), compensationFailures,
                            recovered, unrecovered, mean(recoveryTimes)),
                    new ReliabilityReport.Latency(percentile(runLatencies, 50), percentile(runLatencies, 95),
                            percentile(runLatencies, 100), percentile(approvalWaits, 50), percentile(approvalWaits, 100)),
                    new ReliabilityReport.Governance(approvalsRequested, approved, rejected, expired, withdrawn,
                            policyBlocks, policyEscalations, safeStops, ratio(humanTouchedRuns, finishedRuns)),
                    new ReliabilityReport.Replanning(revisions, invalidations, earlyCutoffs));
        }

        private static double ratio(long part, long whole) {
            return whole == 0 ? 0.0 : Math.round(1000.0 * part / whole) / 1000.0;
        }

        private static Long mean(List<Long> values) {
            return values.isEmpty() ? null : Math.round(values.stream().mapToLong(Long::longValue).average().orElse(0));
        }

        /** Nearest-rank percentile; null when there is no data (rather than a misleading 0). */
        static Long percentile(List<Long> values, int p) {
            if (values.isEmpty()) {
                return null;
            }
            List<Long> sorted = values.stream().sorted().toList();
            int rank = (int) Math.ceil(p / 100.0 * sorted.size());
            return sorted.get(Math.max(0, Math.min(sorted.size() - 1, rank - 1)));
        }
    }
}
