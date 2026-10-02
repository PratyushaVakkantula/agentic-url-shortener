package com.agentic.orchestration.api;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import tools.jackson.databind.JsonNode;

public record RevisionRequest(
        @Schema(description = "Replacement content (JSON object)") @NotNull JsonNode content,
        @Schema(example = "Product owner clarified: limit applies per link, not per user") @NotBlank @Size(max = 2000) String reason) {
}
