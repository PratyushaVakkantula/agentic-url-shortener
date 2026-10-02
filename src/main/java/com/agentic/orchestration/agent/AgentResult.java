package com.agentic.orchestration.agent;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * What an agent proposes: an output (any JSON-serialisable value, stored as a versioned
 * artifact) and the decisions it made along the way.
 */
public record AgentResult(Object output, List<AgentDecision> decisions) {

    public AgentResult {
        Objects.requireNonNull(output, "output");
        decisions = List.copyOf(decisions);
    }

    public static AgentResult of(Object output) {
        return new AgentResult(output, List.of());
    }

    public AgentResult withDecision(String summary, String rationale) {
        List<AgentDecision> more = new ArrayList<>(decisions);
        more.add(new AgentDecision(summary, rationale));
        return new AgentResult(output, more);
    }

    public record AgentDecision(String summary, String rationale) {
        public AgentDecision {
            Objects.requireNonNull(summary, "summary");
            rationale = rationale == null ? "" : rationale;
        }
    }
}
