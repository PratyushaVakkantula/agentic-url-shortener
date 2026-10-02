package com.agentic.orchestration.model;

/** Points at one exact version of a stage's output; the unit of decision lineage. */
public record ArtifactRef(String stageId, int version, String contentHash) {

    @Override
    public String toString() {
        return stageId + "@v" + version + "#" + contentHash.substring(0, Math.min(12, contentHash.length()));
    }
}
