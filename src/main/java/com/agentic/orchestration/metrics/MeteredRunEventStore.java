package com.agentic.orchestration.metrics;

import com.agentic.orchestration.event.RunEvent;
import com.agentic.orchestration.event.RunEventCodec;
import com.agentic.orchestration.event.RunEventStore;
import com.agentic.orchestration.event.RunSummary;
import com.agentic.orchestration.model.RunStatus;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;
import java.util.List;

/**
 * Decorator that turns every appended event into live Micrometer metrics, so operational
 * dashboards and alerts (Prometheus, Datadog...) get counters without the engine knowing about
 * metrics at all. Complements {@link ReliabilityMetrics}, which is exact but computed on demand.
 *
 * <p>Meters: {@code orchestration.events{type}}, {@code orchestration.runs.completed{workflow,status}},
 * {@code orchestration.run.duration{workflow,status}}, {@code orchestration.stage.retries},
 * {@code orchestration.rollbacks}, {@code orchestration.approvals{decision}},
 * {@code orchestration.policy.blocks}.
 */
public class MeteredRunEventStore implements RunEventStore {

    private final RunEventStore delegate;
    private final MeterRegistry meters;

    public MeteredRunEventStore(RunEventStore delegate, MeterRegistry meters) {
        this.delegate = delegate;
        this.meters = meters;
    }

    @Override
    public void append(RunEvent event) {
        delegate.append(event); // record only what was actually persisted
        meters.counter("orchestration.events", "type", RunEventCodec.typeOf(event)).increment();
        switch (event) {
            case RunEvent.RunCompleted e -> recordCompletion(e);
            case RunEvent.RetryScheduled e -> meters.counter("orchestration.stage.retries").increment();
            case RunEvent.RollbackStarted e -> meters.counter("orchestration.rollbacks").increment();
            case RunEvent.ApprovalDecided e -> meters.counter("orchestration.approvals", "decision", e.decision().name()).increment();
            case RunEvent.PolicyEvaluated e when e.outcome().name().equals("BLOCK") ->
                    meters.counter("orchestration.policy.blocks", "policy", e.policy()).increment();
            default -> { }
        }
    }

    private void recordCompletion(RunEvent.RunCompleted completed) {
        // Start time comes from the log, so runs resumed after a restart are measured correctly.
        RunEvent.RunStarted started = (RunEvent.RunStarted) delegate.load(completed.runId()).getFirst();
        String status = completed.status().name();
        meters.counter("orchestration.runs.completed", "workflow", started.workflow(), "status", status).increment();
        Timer.builder("orchestration.run.duration").tags("workflow", started.workflow(), "status", status)
                .register(meters).record(Duration.between(started.at(), completed.at()));
    }

    @Override
    public List<RunEvent> load(String runId) {
        return delegate.load(runId);
    }

    @Override
    public List<RunSummary> runs() {
        return delegate.runs();
    }

    @Override
    public List<String> runIdsWithStatus(RunStatus status) {
        return delegate.runIdsWithStatus(status);
    }
}
