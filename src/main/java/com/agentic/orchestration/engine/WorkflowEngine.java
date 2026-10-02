package com.agentic.orchestration.engine;

import com.agentic.orchestration.definition.WorkflowDefinition;
import com.agentic.orchestration.event.RunEvent;
import com.agentic.orchestration.event.RunEventStore;
import com.agentic.orchestration.event.RunSummary;
import com.agentic.orchestration.model.Requirement;
import com.agentic.orchestration.model.RunStatus;
import com.agentic.orchestration.state.RunState;
import com.agentic.orchestration.state.RunView;
import jakarta.annotation.PreDestroy;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * Entry point of the orchestration layer: starts runs, resumes interrupted ones, and exposes
 * state and the audit log. Each live run is driven by its own {@link RunCoordinator}; agents
 * share one virtual-thread pool.
 */
@Component
public class WorkflowEngine {

    private static final Logger log = LoggerFactory.getLogger(WorkflowEngine.class);

    private final RunEventStore store;
    private final WorkflowCatalog catalog;
    private final JsonMapper mapper;
    private final Clock clock;
    private final ExecutorService agentExecutor = Executors.newVirtualThreadPerTaskExecutor();
    private final Map<String, RunCoordinator> live = new ConcurrentHashMap<>();

    public WorkflowEngine(RunEventStore store, WorkflowCatalog catalog, JsonMapper mapper, Clock clock) {
        this.store = store;
        this.catalog = catalog;
        this.mapper = mapper;
        this.clock = clock;
    }

    public String start(String workflowName, Requirement requirement, String initiator) {
        WorkflowDefinition definition = catalog.find(workflowName).orElseThrow(() -> new UnknownWorkflowException(workflowName));
        return start(definition, requirement, initiator);
    }

    /** Starts a run asynchronously and returns its id immediately. */
    public String start(WorkflowDefinition definition, Requirement requirement, String initiator) {
        String runId = UUID.randomUUID().toString();
        RunCoordinator coordinator = newCoordinator(runId, definition);
        live.put(runId, coordinator);
        coordinator.start(requirement, initiator);
        log.info("Started run {} of workflow {} v{} for {}", runId, definition.name(), definition.version(), initiator);
        return runId;
    }

    /**
     * Resumes every run the store reports as RUNNING but that has no live coordinator, i.e.
     * runs interrupted by a restart or crash (OR-15). A run whose workflow definition is gone or
     * has a different version is failed with an explicit reason, never silently continued under
     * a different process definition.
     *
     * <p>Assumes one engine instance per database. With several instances, the store's sequence
     * check makes a second resumer fail its first append rather than corrupt the log, but
     * instances should coordinate with a lease before resuming (documented limitation).
     *
     * @return ids of runs resumed
     */
    public List<String> resumeUnfinishedRuns() {
        List<String> resumed = new ArrayList<>();
        for (String runId : store.runIdsWithStatus(RunStatus.RUNNING)) {
            if (live.containsKey(runId)) {
                continue;
            }
            List<RunEvent> history = store.load(runId);
            RunEvent.RunStarted started = (RunEvent.RunStarted) history.getFirst();
            Optional<WorkflowDefinition> definition = catalog.find(started.workflow())
                    .filter(d -> d.version() == started.workflowVersion());
            if (definition.isEmpty()) {
                String reason = "cannot resume: workflow " + started.workflow() + " v" + started.workflowVersion()
                        + " is no longer deployed";
                long nextSeq = RunState.replay(history).lastSeq() + 1;
                store.append(new RunEvent.RunCompleted(runId, nextSeq, clock.instant(), RunStatus.FAILED, reason));
                log.warn("Run {} abandoned: {}", runId, reason);
                continue;
            }
            RunCoordinator coordinator = newCoordinator(runId, definition.get());
            live.put(runId, coordinator);
            coordinator.resume(history);
            resumed.add(runId);
            log.info("Resumed run {} ({} v{})", runId, started.workflow(), started.workflowVersion());
        }
        return resumed;
    }

    /** Live runs come from their coordinator; finished or foreign runs are rebuilt from the log. */
    public Optional<RunView> find(String runId) {
        RunCoordinator coordinator = live.get(runId);
        if (coordinator != null) {
            return Optional.of(coordinator.view());
        }
        List<RunEvent> events = store.load(runId);
        return events.isEmpty() ? Optional.empty() : Optional.of(RunState.replay(events).view());
    }

    public RunView get(String runId) {
        return find(runId).orElseThrow(() -> new UnknownRunException(runId));
    }

    public List<RunSummary> runs() {
        return store.runs();
    }

    public List<RunEvent> events(String runId) {
        List<RunEvent> events = store.load(runId);
        if (events.isEmpty()) {
            throw new UnknownRunException(runId);
        }
        return events;
    }

    /** Blocks until the run reaches a terminal status. Intended for tests and synchronous callers. */
    public RunView awaitCompletion(String runId, Duration timeout) throws TimeoutException, InterruptedException {
        RunCoordinator coordinator = live.get(runId);
        if (coordinator == null) {
            return get(runId);
        }
        try {
            return coordinator.completion().get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (ExecutionException e) {
            throw new IllegalStateException("Run " + runId + " coordinator failed", e.getCause());
        }
    }

    /**
     * Stops all coordinators without recording anything (see {@link RunCoordinator#stop()}),
     * then the agent pool. Unfinished runs stay RUNNING in the store and resume on next start.
     */
    @PreDestroy
    public void shutdown() {
        for (RunCoordinator coordinator : live.values()) {
            try {
                coordinator.stop();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        agentExecutor.shutdownNow();
    }

    private RunCoordinator newCoordinator(String runId, WorkflowDefinition definition) {
        return new RunCoordinator(runId, definition, store, agentExecutor, mapper, clock);
    }
}
