package com.agentic.orchestration.event;

import com.agentic.orchestration.model.RunStatus;
import java.util.List;

/**
 * Append-only event log per run, plus a summary read model.
 *
 * <p>Contract: events of a run are appended with gap-free, strictly increasing {@code seq}
 * starting at 1. An append that does not continue the sequence is rejected with
 * {@link ConcurrentRunModificationException}; this is the single-writer guarantee.
 */
public interface RunEventStore {

    void append(RunEvent event);

    List<RunEvent> load(String runId);

    /** Newest first. */
    List<RunSummary> runs();

    List<String> runIdsWithStatus(RunStatus status);
}
