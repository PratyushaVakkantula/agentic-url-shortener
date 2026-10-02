package com.agentic.orchestration.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record StopRunRequest(@NotBlank @Size(max = 1000) String reason) {
}
