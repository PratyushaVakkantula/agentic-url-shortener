package com.agentic.orchestration.engine;

import com.agentic.orchestration.definition.WorkflowDefinition;
import com.agentic.orchestration.event.RunEvent;
import com.agentic.orchestration.event.RunEventStore;
import com.agentic.orchestration.model.Requirement;
import com.agentic.orchestration.state.RunView;
import jakarta.annotation.PreDestroy;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * Entry point of the orchestration layer: starts runs and exposes their state and event log.
 * Each run is driven by its own {@link RunCoordinator}; agents share one virtual-thread pool,
 * so a stage blocked on I/O costs almost nothing and parallel branches really run in parallel.
 */
@Component
public class WorkflowEngine {

    private final RunEventStore store;
    private final JsonMapper mapper;
    private final Clock clock;
    private final ExecutorService agentExecutor = Executors.newVirtualThreadPerTaskExecutor();
    private final Map<String, RunCoordinator> runs = new ConcurrentHashMap<>();

    public WorkflowEngine(RunEventStore store, JsonMapper mapper, Clock clock) {
        this.store = store;
        this.mapper = mapper;
        this.clock = clock;
    }

    /** Starts a run asynchronously and returns its id immediately. */
    public String start(WorkflowDefinition definition, Requirement requirement, String initiator) {
        String runId = UUID.randomUUID().toString();
        RunCoordinator coordinator = new RunCoordinator(runId, definition, store, agentExecutor, mapper, clock);
        runs.put(runId, coordinator);
        coordinator.start(requirement, initiator);
        return runId;
    }

    public Optional<RunView> find(String runId) {
        return Optional.ofNullable(runs.get(runId)).map(RunCoordinator::view);
    }

    public List<RunEvent> events(String runId) {
        return store.load(runId);
    }

    /** Blocks until the run reaches a terminal status. Intended for tests and synchronous callers. */
    public RunView awaitCompletion(String runId, Duration timeout) throws TimeoutException, InterruptedException {
        RunCoordinator coordinator = runs.get(runId);
        if (coordinator == null) {
            throw new IllegalArgumentException("Unknown run " + runId);
        }
        try {
            return coordinator.completion().get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (ExecutionException e) {
            throw new IllegalStateException("Run " + runId + " coordinator failed", e.getCause());
        }
    }

    @PreDestroy
    void shutdown() {
        agentExecutor.shutdownNow();
    }
}
