package com.agentic.orchestration.agents;

import com.agentic.orchestration.agent.Agent;
import com.agentic.orchestration.agent.AgentResult;
import com.agentic.orchestration.agent.StageContext;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import tools.jackson.databind.JsonNode;

/**
 * Plans validation (core requirement 6): one automated test per acceptance criterion, a migration
 * upgrade test per schema change, a contract test per API change, and regression re-runs of the
 * existing tests found by impact analysis.
 *
 * <p>A criterion stated only in vague terms ("fast") is <b>not</b> testable. It counts as covered
 * only if an assumption made it measurable. Otherwise it is listed in {@code uncoveredCriteria},
 * which the workflow's exit gate rejects: a plan that cannot prove "done" does not pass.
 */
public class TestPlannerAgent implements Agent {

    public record TestCase(String id, String type, String title, List<String> covers) {
    }

    public record TestPlan(List<TestCase> testCases, Map<String, List<String>> coverage, List<String> uncoveredCriteria,
                           List<String> regressionSuites) {
    }

    @Override
    public String name() {
        return "test-planner";
    }

    @Override
    public AgentResult execute(StageContext context) {
        JsonNode requirements = context.artifact("requirements").content();
        JsonNode design = context.artifact("design").content();
        JsonNode impact = context.hasArtifact("impact-analysis") ? context.artifact("impact-analysis").content() : null;
        TestPlan plan = plan(requirements, design, impact);
        return AgentResult.of(plan).withDecision(plan.testCases().size() + " test case(s); "
                        + plan.uncoveredCriteria().size() + " criterion(a) not provable",
                "every acceptance criterion must map to an automated test; vague criteria need a measurable assumption");
    }

    TestPlan plan(JsonNode requirements, JsonNode design, JsonNode impact) {
        List<TestCase> tests = new ArrayList<>();
        Map<String, List<String>> coverage = new LinkedHashMap<>();
        List<String> uncovered = new ArrayList<>();

        Map<String, String> qualityByQuestion = new HashMap<>();
        Json.objects(requirements, "ambiguities").forEach(a -> qualityByQuestion.put(a.path("id").asString(), a.path("quality").asString()));
        Map<String, String> assumptionByQuality = new HashMap<>();
        Json.objects(requirements, "assumptions").forEach(a -> assumptionByQuality.put(
                qualityByQuestion.getOrDefault(a.path("resolves").asString(), ""), a.path("id").asString() + ": " + a.path("statement").asString()));

        String changeType = Json.text(requirements, "changeType", "FEATURE");
        if (changeType.equals("BUG_FIX")) {
            tests.add(new TestCase("TC-1", "regression",
                    "reproduces the reported bug: \"" + Json.text(requirements, "title", "") + "\" (must fail before the fix, pass after)",
                    List.of()));
        } else if (changeType.equals("REFACTOR")) {
            tests.add(new TestCase("TC-1", "regression", "existing suites pass unchanged (behaviour preserved)", List.of()));
        }
        for (JsonNode criterion : Json.objects(requirements, "acceptanceCriteria")) {
            String id = criterion.path("id").asString();
            String text = criterion.path("text").asString();
            String vagueQuality = RequirementsAnalystAgent.VAGUE_TERMS.stream()
                    .filter(v -> v.pattern().matcher(text).find()).map(RequirementsAnalystAgent.Vagueness::quality)
                    .findFirst().orElse(null);
            String testId = "TC-" + (tests.size() + 1);
            if (vagueQuality == null) {
                tests.add(new TestCase(testId, text.matches("(?i).*\\b(\\d{3}|return|respond|redirect|api|request)\\b.*") ? "integration" : "unit",
                        "verifies " + id + ": " + text, List.of(id)));
                coverage.put(id, List.of(testId));
            } else if (assumptionByQuality.containsKey(vagueQuality)) {
                tests.add(new TestCase(testId, vagueQuality.equals("performance") ? "load" : "integration",
                        "verifies " + id + " via assumption " + assumptionByQuality.get(vagueQuality), List.of(id)));
                coverage.put(id, List.of(testId));
            } else {
                uncovered.add(id + " (\"" + text + "\" is not measurable)");
            }
        }
        for (JsonNode schema : Json.objects(design, "schemaChanges")) {
            tests.add(new TestCase("TC-" + (tests.size() + 1), "migration",
                    "upgrade test: data written by the previous schema is valid after " + schema.path("migration").asString(), List.of()));
        }
        for (String api : Json.strings(design, "apiChanges")) {
            tests.add(new TestCase("TC-" + (tests.size() + 1), "contract", "API contract: " + api, List.of()));
        }
        List<String> regression = impact == null ? List.of() : Json.strings(impact, "existingTests");
        return new TestPlan(tests, coverage, uncovered, regression);
    }
}
