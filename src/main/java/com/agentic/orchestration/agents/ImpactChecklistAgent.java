package com.agentic.orchestration.agents;

import com.agentic.orchestration.agent.Agent;
import com.agentic.orchestration.agent.AgentResult;
import com.agentic.orchestration.agent.StageContext;
import com.agentic.orchestration.agents.ImpactAnalysisAgent.Impact;
import java.util.List;
import java.util.Map;

/**
 * Fallback for {@link ImpactAnalysisAgent} when automated analysis is unavailable (e.g. the
 * service runs from a packaged jar without sources). Conservative by design: it claims nothing
 * about which code is affected, rates the risk HIGH and asks for a manual review. Degrading to
 * "ask a person" is safer than degrading to a guess. Same output schema as the primary agent, so
 * downstream agents need no special case.
 */
public class ImpactChecklistAgent implements Agent {

    static final String MANUAL_REVIEW = "automated impact analysis unavailable; manual review required: "
            + "identify affected modules and endpoints, tables and migrations, and regression suites to run";

    @Override
    public String name() {
        return "impact-checklist";
    }

    @Override
    public AgentResult execute(StageContext context) {
        Impact impact = new Impact(List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(),
                null, "HIGH", MANUAL_REVIEW, Map.of());
        return AgentResult.of(impact).withDecision("fell back to manual impact review", "static analysis failed; never guess impact");
    }
}
