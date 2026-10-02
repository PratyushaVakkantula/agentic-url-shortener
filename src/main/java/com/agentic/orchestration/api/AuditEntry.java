package com.agentic.orchestration.api;

import com.agentic.orchestration.event.RunEvent;
import com.agentic.orchestration.event.RunEventCodec;
import java.time.Instant;

/** One line of a run's audit trail: the stored event plus its type for easy filtering. */
public record AuditEntry(long seq, String type, Instant at, RunEvent event) {

    static AuditEntry of(RunEvent event) {
        return new AuditEntry(event.seq(), RunEventCodec.typeOf(event), event.at(), event);
    }
}
