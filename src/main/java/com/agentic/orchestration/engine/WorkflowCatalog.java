package com.agentic.orchestration.engine;

import com.agentic.orchestration.definition.WorkflowDefinition;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/**
 * The workflows this deployment can run, collected from every {@link WorkflowDefinition} bean.
 * Names are unique; a duplicate is a startup error rather than a silent override.
 */
@Component
public class WorkflowCatalog {

    private final Map<String, WorkflowDefinition> byName;

    public WorkflowCatalog(List<WorkflowDefinition> definitions) {
        this.byName = definitions.stream().collect(Collectors.toUnmodifiableMap(WorkflowDefinition::name,
                Function.identity(), (a, b) -> {
                    throw new IllegalStateException("Duplicate workflow name '" + a.name() + "'");
                }));
    }

    public Optional<WorkflowDefinition> find(String name) {
        return Optional.ofNullable(byName.get(name));
    }

    public Collection<WorkflowDefinition> all() {
        return byName.values().stream().sorted(Comparator.comparing(WorkflowDefinition::name)).toList();
    }
}
