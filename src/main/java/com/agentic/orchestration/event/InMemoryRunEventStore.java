package com.agentic.orchestration.event;

import com.agentic.orchestration.model.RunStatus;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Non-durable store with the same contract as the JDBC one; used by fast unit tests. */
public class InMemoryRunEventStore implements RunEventStore {

    private final Map<String, List<RunEvent>> streams = new ConcurrentHashMap<>();

    @Override
    public void append(RunEvent event) {
        List<RunEvent> stream = streams.computeIfAbsent(event.runId(), id -> new ArrayList<>());
        synchronized (stream) {
            if (event.seq() != stream.size() + 1L) {
                throw new ConcurrentRunModificationException(event.runId(), event.seq());
            }
            stream.add(event);
        }
    }

    @Override
    public List<RunEvent> load(String runId) {
        List<RunEvent> stream = streams.getOrDefault(runId, List.of());
        synchronized (stream) {
            return List.copyOf(stream);
        }
    }

    @Override
    public List<RunSummary> runs() {
        return streams.keySet().stream().map(this::summarize)
                .sorted(Comparator.comparing(RunSummary::startedAt).reversed()).toList();
    }

    @Override
    public List<String> runIdsWithStatus(RunStatus status) {
        return runs().stream().filter(r -> r.status() == status).map(RunSummary::runId).toList();
    }

    private RunSummary summarize(String runId) {
        List<RunEvent> events = load(runId);
        RunEvent.RunStarted started = (RunEvent.RunStarted) events.getFirst();
        RunStatus status = RunStatus.RUNNING;
        java.time.Instant finished = null;
        if (events.getLast() instanceof RunEvent.RunCompleted done) {
            status = done.status();
            finished = done.at();
        }
        return new RunSummary(runId, started.workflow(), started.workflowVersion(), started.requirement().title(),
                started.initiator(), status, started.at(), finished, events.size());
    }
}
