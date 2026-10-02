package com.agentic.orchestration.model;

import java.time.Instant;
import java.util.List;
import tools.jackson.databind.JsonNode;

/**
 * Output of one stage execution, or a human revision of it.
 *
 * <p>Versioned per stage and content-addressed: {@code contentHash} is SHA-256 over canonical
 * JSON. {@code derivedFrom} is its provenance: the exact input versions the producing agent read.
 * Together they answer the re-planning question "is this output still valid?" with a comparison:
 * it is stale as soon as any input's current hash differs from the one recorded here (OR-12).
 */
public record Artifact(String stageId, int version, String contentHash, JsonNode content, Instant producedAt,
                       List<ArtifactRef> derivedFrom) {

    public Artifact {
        derivedFrom = derivedFrom == null ? List.of() : List.copyOf(derivedFrom);
    }

    public ArtifactRef ref() {
        return new ArtifactRef(stageId, version, contentHash);
    }
}
