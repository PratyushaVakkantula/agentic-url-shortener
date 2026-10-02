package com.agentic.orchestration.api;

import com.agentic.orchestration.definition.StageDefinition;
import com.agentic.orchestration.definition.WorkflowDefinition;
import java.util.List;

/** A workflow's graph as data: enough for a client to draw it. */
public record WorkflowDescription(String name, int version, List<Stage> stages) {

    public record Stage(String id, String description, String agent, List<String> dependsOn,
                        List<String> entryGates, List<String> exitGates) {
    }

    static WorkflowDescription of(WorkflowDefinition wf) {
        return new WorkflowDescription(wf.name(), wf.version(), wf.topologicalOrder().stream().map(id -> {
            StageDefinition s = wf.stage(id);
            return new Stage(s.id(), s.description(), s.agent().name(), s.dependsOn().stream().sorted().toList(),
                    s.entryGates().stream().map(g -> g.name()).toList(),
                    s.exitGates().stream().map(g -> g.name()).toList());
        }).toList());
    }
}
