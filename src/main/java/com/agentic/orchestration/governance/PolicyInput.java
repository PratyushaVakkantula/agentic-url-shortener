package com.agentic.orchestration.governance;

import com.agentic.orchestration.model.Requirement;
import tools.jackson.databind.JsonNode;

/**
 * What a policy evaluates: one stage's proposed output, in the context of its run.
 *
 * @param outputText the output serialised once, so text-scanning policies share the work
 */
public record PolicyInput(String workflow, String stageId, Requirement requirement, JsonNode output, String outputText) {
}
