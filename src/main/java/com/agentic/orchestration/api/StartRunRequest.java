package com.agentic.orchestration.api;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.Map;

/**
 * A requirement to run through a workflow. Deliberately has no "initiator" field: the initiator
 * is always the authenticated caller, so separation of duties cannot be bypassed by the body.
 */
public record StartRunRequest(
        @Schema(example = "Add per-link click limits") @NotBlank @Size(max = 500) String title,
        @Schema(example = "Links should stop redirecting after N clicks.") @Size(max = 10_000) String description,
        @Schema(description = "Optional structured hints for the agents") @Size(max = 20) Map<String, @Size(max = 500) String> attributes) {
}
