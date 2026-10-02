package com.agentic.orchestration.definition;

import com.agentic.orchestration.agent.Agent;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * One node of a workflow graph: what runs ({@link Agent}), what it waits for
 * ({@code dependsOn}), and the gates guarding its entry and exit.
 *
 * <p>Immutable. Built through {@link WorkflowDefinition.Builder}.
 *
 * @param entryGates preconditions checked before the agent runs (e.g. "design artifact names the
 *                   impacted modules"); a failure means the stage cannot start
 * @param exitGates  checks on the agent's output (e.g. "tests reported zero failures"); a failure
 *                   counts as a failed attempt
 */
public record StageDefinition(
        String id,
        String description,
        Agent agent,
        Set<String> dependsOn,
        List<Gate> entryGates,
        List<Gate> exitGates) {

    private static final Pattern ID = Pattern.compile("[a-z][a-z0-9-]{0,40}");

    public StageDefinition {
        Objects.requireNonNull(id, "id");
        if (!ID.matcher(id).matches()) {
            throw new WorkflowDefinitionException("Stage id '" + id + "' must match " + ID.pattern());
        }
        Objects.requireNonNull(agent, "agent of stage " + id);
        description = description == null ? "" : description;
        dependsOn = Set.copyOf(dependsOn);
        entryGates = List.copyOf(entryGates);
        exitGates = List.copyOf(exitGates);
    }
}
