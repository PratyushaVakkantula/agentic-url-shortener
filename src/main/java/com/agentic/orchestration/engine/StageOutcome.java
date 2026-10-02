package com.agentic.orchestration.engine;

import com.agentic.orchestration.event.FailureKind;
import com.agentic.orchestration.governance.PolicyDecision;
import com.agentic.orchestration.model.Decision;
import java.util.List;
import tools.jackson.databind.JsonNode;

/** Result of one stage attempt, posted by the worker thread to the run's mailbox. */
sealed interface StageOutcome extends RunCoordinator.Signal {

    String stageId();

    int attempt();

    record Completed(String stageId, int attempt, JsonNode output, List<Decision> decisions,
                     List<GateCheck> exitGates, List<PolicyDecision> policies, long durationMillis) implements StageOutcome {
    }

    record Errored(String stageId, int attempt, FailureKind kind, String reason,
                   long durationMillis) implements StageOutcome {
    }

    record GateCheck(String gate, boolean passed, String reason) {
    }
}
