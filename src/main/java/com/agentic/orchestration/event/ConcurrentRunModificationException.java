package com.agentic.orchestration.event;

/**
 * Two writers tried to append to the same run (e.g. two instances recovering it at once).
 * The store rejects the second one; the run's log stays consistent.
 */
public class ConcurrentRunModificationException extends RuntimeException {

    public ConcurrentRunModificationException(String runId, long seq) {
        super("Run " + runId + ": event seq " + seq + " conflicts with another writer");
    }
}
