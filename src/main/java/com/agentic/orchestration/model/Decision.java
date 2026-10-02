package com.agentic.orchestration.model;

import java.time.Instant;
import java.util.List;

/**
 * A recorded agent decision with its lineage (OR-4).
 *
 * @param basedOn the exact artifact versions the agent <i>actually read</i> while deciding,
 *                captured automatically by the stage context, not self-reported by the agent
 */
public record Decision(String stageId, int attempt, String agent, String summary, String rationale,
                       List<ArtifactRef> basedOn, Instant at) {

    public Decision {
        basedOn = List.copyOf(basedOn);
    }
}
