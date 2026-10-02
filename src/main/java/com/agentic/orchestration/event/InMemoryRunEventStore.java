package com.agentic.orchestration.event;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Non-durable store for unit tests. */
public class InMemoryRunEventStore implements RunEventStore {

    private final Map<String, List<RunEvent>> streams = new ConcurrentHashMap<>();

    @Override
    public void append(RunEvent event) {
        List<RunEvent> stream = streams.computeIfAbsent(event.runId(), id -> new ArrayList<>());
        synchronized (stream) {
            long expected = stream.size() + 1L;
            if (event.seq() != expected) {
                throw new IllegalStateException("Run " + event.runId() + ": expected seq " + expected + " but got " + event.seq());
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
    public List<String> runIds() {
        return List.copyOf(streams.keySet());
    }
}
