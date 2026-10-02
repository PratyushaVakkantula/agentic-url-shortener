package com.agentic.orchestration.model;

import java.time.Instant;
import tools.jackson.databind.JsonNode;

/**
 * Output of one stage execution. Versioned per stage (a re-executed stage produces v2, v3...)
 * and content-addressed: {@code contentHash} is SHA-256 over canonical JSON, so "did this
 * output change?" (the re-planning question) is a hash comparison.
 */
public record Artifact(String stageId, int version, String contentHash, JsonNode content, Instant producedAt) {

    public ArtifactRef ref() {
        return new ArtifactRef(stageId, version, contentHash);
    }
}
