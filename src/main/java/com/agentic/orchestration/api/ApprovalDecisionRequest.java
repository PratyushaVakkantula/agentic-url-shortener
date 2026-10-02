package com.agentic.orchestration.api;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * @param artifactHash content hash of the artifact the approver reviewed (from the run view). The
 *                     decision is refused if it no longer matches the pending artifact.
 */
public record ApprovalDecisionRequest(
        @Schema(example = "APPROVE") @NotNull Decision decision,
        @Schema(description = "SHA-256 of the reviewed artifact") @NotBlank @Pattern(regexp = "[0-9a-f]{64}") String artifactHash,
        @Size(max = 2000) String comment) {

    public enum Decision {
        APPROVE,
        REJECT
    }
}
