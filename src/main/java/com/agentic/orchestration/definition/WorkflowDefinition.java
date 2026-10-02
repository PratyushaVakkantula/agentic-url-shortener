package com.agentic.orchestration.definition;

import com.agentic.orchestration.agent.Agent;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/**
 * An explicit, validated dependency graph of stages (OR-1).
 *
 * <p>Validation happens once, at build time: unknown dependencies, self-dependencies and cycles
 * are rejected with a message naming the offending stages. A definition that exists is
 * therefore always executable: the engine never discovers a malformed graph mid-run.
 *
 * <p>Precomputes a deterministic topological order plus ancestor/descendant sets, which the
 * engine uses for scheduling, context isolation (a stage may read only its ancestors) and
 * re-planning (a changed stage invalidates exactly its descendants).
 */
public final class WorkflowDefinition {

    private final String name;
    private final int version;
    private final Map<String, StageDefinition> stages;
    private final List<String> topologicalOrder;
    private final Map<String, Set<String>> dependents;
    private final Map<String, Set<String>> ancestors;
    private final Map<String, Set<String>> descendants;

    private WorkflowDefinition(String name, int version, Map<String, StageDefinition> stages) {
        this.name = name;
        this.version = version;
        this.stages = Collections.unmodifiableMap(new LinkedHashMap<>(stages));
        validateReferences();
        this.dependents = computeDependents();
        this.topologicalOrder = computeTopologicalOrder();
        this.ancestors = closure(id -> this.stages.get(id).dependsOn());
        this.descendants = closure(id -> this.dependents.get(id));
    }

    public static Builder builder(String name, int version) {
        return new Builder(name, version);
    }

    public String name() {
        return name;
    }

    public int version() {
        return version;
    }

    public StageDefinition stage(String id) {
        StageDefinition stage = stages.get(id);
        if (stage == null) {
            throw new IllegalArgumentException("Unknown stage '" + id + "' in workflow " + name);
        }
        return stage;
    }

    public boolean hasStage(String id) {
        return stages.containsKey(id);
    }

    /** Deterministic: among stages that are ready together, declaration order wins. */
    public List<String> topologicalOrder() {
        return topologicalOrder;
    }

    public Set<String> dependentsOf(String id) {
        return dependents.get(id);
    }

    /** All transitive upstream stages. */
    public Set<String> ancestorsOf(String id) {
        return ancestors.get(id);
    }

    /** All transitive downstream stages. */
    public Set<String> descendantsOf(String id) {
        return descendants.get(id);
    }

    private void validateReferences() {
        if (stages.isEmpty()) {
            throw new WorkflowDefinitionException("Workflow '" + name + "' has no stages");
        }
        stages.values().forEach(stage -> stage.dependsOn().forEach(dep -> {
            if (dep.equals(stage.id())) {
                throw new WorkflowDefinitionException("Stage '" + stage.id() + "' depends on itself");
            }
            if (!stages.containsKey(dep)) {
                throw new WorkflowDefinitionException(
                        "Stage '" + stage.id() + "' depends on unknown stage '" + dep + "'");
            }
        }));
    }

    private Map<String, Set<String>> computeDependents() {
        Map<String, Set<String>> result = new LinkedHashMap<>();
        stages.keySet().forEach(id -> result.put(id, new LinkedHashSet<>()));
        stages.values().forEach(stage -> stage.dependsOn().forEach(dep -> result.get(dep).add(stage.id())));
        result.replaceAll((k, v) -> Collections.unmodifiableSet(v));
        return Collections.unmodifiableMap(result);
    }

    /** Kahn's algorithm; leftover nodes mean a cycle, which is then located for the error message. */
    private List<String> computeTopologicalOrder() {
        Map<String, Integer> inDegree = new HashMap<>();
        stages.values().forEach(s -> inDegree.put(s.id(), s.dependsOn().size()));

        List<String> order = new ArrayList<>();
        Deque<String> ready = new ArrayDeque<>();
        stages.keySet().stream().filter(id -> inDegree.get(id) == 0).forEach(ready::add);
        while (!ready.isEmpty()) {
            String id = ready.poll();
            order.add(id);
            for (String dependent : orderedDependents(id)) {
                if (inDegree.merge(dependent, -1, Integer::sum) == 0) {
                    ready.add(dependent);
                }
            }
        }
        if (order.size() != stages.size()) {
            throw new WorkflowDefinitionException("Workflow '" + name + "' contains a cycle: " + findCycle());
        }
        return List.copyOf(order);
    }

    private List<String> orderedDependents(String id) {
        // Iterate in declaration order so the topological order is stable across runs.
        return stages.keySet().stream().filter(dependents.get(id)::contains).toList();
    }

    private String findCycle() {
        Set<String> done = new LinkedHashSet<>();
        for (String start : stages.keySet()) {
            List<String> path = new ArrayList<>();
            String cycle = dfs(start, path, new LinkedHashSet<>(), done);
            if (cycle != null) {
                return cycle;
            }
        }
        return "(unknown)";
    }

    private String dfs(String id, List<String> path, Set<String> onPath, Set<String> done) {
        if (onPath.contains(id)) {
            List<String> cycle = new ArrayList<>(path.subList(path.indexOf(id), path.size()));
            cycle.add(id);
            return String.join(" -> ", cycle);
        }
        if (done.contains(id)) {
            return null;
        }
        path.add(id);
        onPath.add(id);
        for (String next : dependents.get(id)) {
            String cycle = dfs(next, path, onPath, done);
            if (cycle != null) {
                return cycle;
            }
        }
        path.remove(path.size() - 1);
        onPath.remove(id);
        done.add(id);
        return null;
    }

    private Map<String, Set<String>> closure(Function<String, Set<String>> edges) {
        Map<String, Set<String>> result = new LinkedHashMap<>();
        for (String id : stages.keySet()) {
            Set<String> seen = new LinkedHashSet<>();
            Deque<String> todo = new ArrayDeque<>(edges.apply(id));
            while (!todo.isEmpty()) {
                String next = todo.poll();
                if (seen.add(next)) {
                    todo.addAll(edges.apply(next));
                }
            }
            result.put(id, Collections.unmodifiableSet(seen));
        }
        return Collections.unmodifiableMap(result);
    }

    public static final class Builder {

        private final String name;
        private final int version;
        private final Map<String, StageDefinition> stages = new LinkedHashMap<>();

        private Builder(String name, int version) {
            this.name = name;
            this.version = version;
        }

        public StageBuilder stage(String id, Agent agent) {
            return new StageBuilder(this, id, agent);
        }

        Builder add(StageDefinition stage) {
            if (stages.putIfAbsent(stage.id(), stage) != null) {
                throw new WorkflowDefinitionException("Duplicate stage id '" + stage.id() + "'");
            }
            return this;
        }

        public WorkflowDefinition build() {
            return new WorkflowDefinition(name, version, stages);
        }
    }

    public static final class StageBuilder {

        private final Builder parent;
        private final String id;
        private final Agent agent;
        private String description;
        private final Set<String> dependsOn = new LinkedHashSet<>();
        private final List<Gate> entryGates = new ArrayList<>();
        private final List<Gate> exitGates = new ArrayList<>();
        private StagePolicy policy = StagePolicy.DEFAULT;

        private StageBuilder(Builder parent, String id, Agent agent) {
            this.parent = parent;
            this.id = id;
            this.agent = agent;
        }

        public StageBuilder description(String description) {
            this.description = description;
            return this;
        }

        public StageBuilder dependsOn(String... stageIds) {
            dependsOn.addAll(List.of(stageIds));
            return this;
        }

        public StageBuilder entryGate(Gate gate) {
            entryGates.add(gate);
            return this;
        }

        public StageBuilder exitGate(Gate gate) {
            exitGates.add(gate);
            return this;
        }

        public StageBuilder retry(int maxAttempts, Duration initialBackoff) {
            policy = policy.withRetry(RetryPolicy.of(maxAttempts, initialBackoff));
            return this;
        }

        public StageBuilder timeout(Duration timeout) {
            policy = policy.withTimeout(timeout);
            return this;
        }

        public StageBuilder fallback(Agent fallback) {
            policy = policy.withFallback(fallback);
            return this;
        }

        public StageBuilder compensation(Compensation compensation) {
            policy = policy.withCompensation(compensation);
            return this;
        }

        /** Marks the stage high-impact: its output always needs human approval. */
        public StageBuilder requiresApproval(String reason) {
            policy = policy.withApproval(reason);
            return this;
        }

        /** Finishes this stage and continues with the workflow builder. */
        public Builder add() {
            return parent.add(new StageDefinition(id, description, agent, dependsOn, entryGates, exitGates, policy));
        }
    }
}
