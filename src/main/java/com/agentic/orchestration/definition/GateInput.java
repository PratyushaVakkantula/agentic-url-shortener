package com.agentic.orchestration.definition;

import com.agentic.orchestration.agent.StageContext;
import tools.jackson.databind.JsonNode;

/**
 * What a gate can see.
 *
 * @param output the agent's output for exit gates; {@code null} for entry gates
 */
public record GateInput(StageContext context, JsonNode output) {
}
