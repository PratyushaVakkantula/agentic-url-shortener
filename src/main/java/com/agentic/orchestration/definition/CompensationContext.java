package com.agentic.orchestration.definition;

import com.agentic.orchestration.model.Artifact;

/** What a compensation knows: which run and stage, and the artifact being undone. */
public record CompensationContext(String runId, String stageId, Artifact artifact) {
}
