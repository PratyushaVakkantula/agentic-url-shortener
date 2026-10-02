package com.agentic.orchestration.metrics;

/**
 * Reliability metrics over a set of runs (OR-11). Rates are 0..1; durations are milliseconds.
 * Every definition is written next to its field so the numbers are reproducible from the audit log.
 */
public record ReliabilityReport(Runs runs, Stages stages, Recovery recovery, Latency latency,
                                Governance governance, Replanning replanning) {

    /** @param successRate succeeded / finished runs (running runs excluded) */
    public record Runs(long total, long running, long succeeded, long failed, long stopped, double successRate) {
    }

    /**
     * @param retryRate       retries / attempts
     * @param timeoutRate     timed-out attempts / attempts
     * @param firstPassYield  stages that succeeded on their first attempt / stages that succeeded
     */
    public record Stages(long attempts, long retries, long fallbacks, long timeouts, double retryRate,
                         double timeoutRate, double firstPassYield) {
    }

    /**
     * @param rollbackFrequency runs that rolled back / finished runs
     * @param recoveredFailures stage failures followed by success of the same stage (via retry or fallback)
     * @param mttrMillis        mean time to recovery: first failed attempt of a stage → that stage's success
     */
    public record Recovery(long rollbacks, double rollbackFrequency, long compensationFailures,
                           long recoveredFailures, long unrecoveredFailures, Long mttrMillis) {
    }

    /** End-to-end: RunStarted → RunCompleted of finished runs; nearest-rank percentiles. */
    public record Latency(Long p50Millis, Long p95Millis, Long maxMillis, Long approvalWaitP50Millis,
                          Long approvalWaitMaxMillis) {
    }

    /** @param humanInterventionRate runs with at least one human decision or stop / finished runs */
    public record Governance(long approvalsRequested, long approved, long rejected, long expired, long withdrawn,
                             long policyBlocks, long policyEscalations, long safeStops, double humanInterventionRate) {
    }

    /** @param earlyCutoffs re-runs that reproduced identical content, so the cascade stopped there */
    public record Replanning(long revisions, long invalidations, long earlyCutoffs) {
    }
}
