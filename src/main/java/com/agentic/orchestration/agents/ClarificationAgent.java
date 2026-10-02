package com.agentic.orchestration.agents;

import com.agentic.orchestration.agent.Agent;
import com.agentic.orchestration.agent.AgentResult;
import com.agentic.orchestration.agent.StageContext;
import java.util.List;
import tools.jackson.databind.JsonNode;

/**
 * Prepares the human checkpoint for an ambiguous requirement: the open questions and the
 * assumptions the agents would proceed with. The approver either confirms them (approve) or
 * corrects the requirements artifact (revision), which re-plans this stage and everything after it.
 */
public class ClarificationAgent implements Agent {

    public record ClarificationPacket(double clarityScore, List<String> openQuestions, List<String> proposedAssumptions,
                                      String recommendation) {
    }

    @Override
    public String name() {
        return "clarification-facilitator";
    }

    @Override
    public AgentResult execute(StageContext context) {
        JsonNode requirements = context.artifact("requirements").content();
        double clarity = requirements.path("clarityScore").asDouble(0);
        List<String> questions = Json.objects(requirements, "ambiguities").stream()
                .map(a -> a.path("id").asString() + " [" + a.path("term").asString() + "] " + a.path("question").asString()).toList();
        List<String> assumptions = Json.objects(requirements, "assumptions").stream()
                .map(a -> a.path("id").asString() + ": " + a.path("statement").asString()).toList();
        String recommendation = questions.isEmpty() ? "requirements are clear; confirm and proceed"
                : clarity < 0.5 ? "clarify before building: too much is assumed"
                : "proceed with the listed assumptions unless a stakeholder corrects them";
        return AgentResult.of(new ClarificationPacket(clarity, questions, assumptions, recommendation))
                .withDecision(recommendation, questions.size() + " open question(s), clarity " + clarity);
    }
}
