package com.agentic.orchestration.agents;

import com.agentic.orchestration.agent.Agent;
import com.agentic.orchestration.agent.AgentResult;
import com.agentic.orchestration.agent.StageContext;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import tools.jackson.databind.JsonNode;

/**
 * Turns a design into a <b>proposed</b> changeset and an ordered task plan (core requirement 2:
 * task decomposition with dependencies). It never edits files: the changeset is a proposal for
 * humans and policies to review (A-9, OR-13). The change-control policy inspects
 * {@code changes} (migrations, security config, blast radius).
 *
 * <p>Tasks follow the dependency order of the layers, so each task lists the tasks it needs:
 * migration → entity → repository → service → controller/dto → cross-cutting.
 */
public class ImplementationPlannerAgent implements Agent {

    private static final List<String> LAYER_ORDER = List.of("migration", "entity", "repository", "service", "dto",
            "controller", "other", "cross-cutting");
    private static final Map<String, int[]> SIZE = Map.of( // kind → {lines if ADD, lines if MODIFY}
            "migration", new int[] {8, 4}, "entity", new int[] {60, 20}, "repository", new int[] {20, 8},
            "service", new int[] {90, 35}, "controller", new int[] {70, 25}, "dto", new int[] {25, 10},
            "cross-cutting", new int[] {80, 30}, "other", new int[] {40, 15});

    public record Change(String path, String operation, int linesChanged, String summary) {
    }

    public record Task(String id, String title, List<String> dependsOn) {
    }

    public record Plan(String branch, List<Change> changes, List<Task> tasks, List<Map<String, String>> dependencies,
                       int totalLines) {
    }

    @Override
    public String name() {
        return "implementation-planner";
    }

    @Override
    public AgentResult execute(StageContext context) {
        JsonNode design = context.artifact("design").content();
        Plan plan = plan(design, context.runId());
        return AgentResult.of(plan).withDecision(
                plan.changes().size() + " file change(s), " + plan.totalLines() + " lines, " + plan.tasks().size() + " task(s)",
                "tasks ordered by layer dependencies so each can be reviewed and merged incrementally");
    }

    Plan plan(JsonNode design, String runId) {
        List<JsonNode> components = new ArrayList<>(Json.objects(design, "components"));
        components.sort((a, b) -> Integer.compare(rank(a.path("kind").asString()), rank(b.path("kind").asString())));

        List<Change> changes = new ArrayList<>();
        List<Task> tasks = new ArrayList<>();
        Map<String, List<String>> tasksByKind = new LinkedHashMap<>();
        List<Map<String, String>> dependencies = new ArrayList<>();
        int total = 0;
        for (JsonNode c : components) {
            String kind = c.path("kind").asString("other");
            String op = c.path("change").asString("MODIFY");
            int lines = SIZE.getOrDefault(kind, SIZE.get("other"))[op.equals("ADD") ? 0 : 1];
            String path = c.path("path").asString();
            if (!path.startsWith("(")) {
                changes.add(new Change(path, op, lines, c.path("reason").asString("")));
                total += lines;
            }
            if (c.path("name").asString().equals("LatencyBudget")) {
                dependencies.add(Map.of("name", "com.github.ben-manes.caffeine:caffeine", "version", "3.2.0", "license", "Apache-2.0"));
            }
            String id = "T-" + (tasks.size() + 1);
            List<String> needs = new ArrayList<>();
            for (String earlier : LAYER_ORDER.subList(0, rank(kind))) {
                needs.addAll(tasksByKind.getOrDefault(earlier, List.of()));
            }
            tasks.add(new Task(id, (op.equals("ADD") ? "Add " : "Update ") + c.path("name").asString() + " (" + kind + ")", needs));
            tasksByKind.computeIfAbsent(kind, k -> new ArrayList<>()).add(id);
        }
        return new Plan("agent/" + runId.substring(0, 8), changes, tasks, dependencies, total);
    }

    private static int rank(String kind) {
        int i = LAYER_ORDER.indexOf(kind);
        return i < 0 ? LAYER_ORDER.indexOf("other") : i;
    }
}
