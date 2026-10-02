package com.agentic.orchestration.event;

import java.util.List;

/**
 * Append-only event log per run. Implementations must preserve order and reject gaps or
 * duplicates in {@code seq}, which protects against two writers for one run.
 */
public interface RunEventStore {

    void append(RunEvent event);

    List<RunEvent> load(String runId);

    List<String> runIds();
}
