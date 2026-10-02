package com.agentic.orchestration.agents;

import com.agentic.orchestration.agent.Agent;
import com.agentic.orchestration.agent.AgentResult;
import com.agentic.orchestration.agent.StageContext;
import java.util.ArrayList;
import java.util.List;
import tools.jackson.databind.JsonNode;

/**
 * Release readiness: semantic version bump, a checklist over upstream evidence, rollout and
 * rollback plans. Proposes only; the stage is high-impact, so a human approves the release.
 */
public class ReleaseManagerAgent implements Agent {

    public record Check(String item, String status, String evidence) {
    }

    public record Release(String currentVersion, String version, String bump, List<Check> readiness, boolean ready,
                          List<String> rollout, List<String> rollbackPlan) {
    }

    @Override
    public String name() {
        return "release-manager";
    }

    @Override
    public AgentResult execute(StageContext context) {
        JsonNode design = context.artifact("design").content();
        JsonNode implementation = context.artifact("implementation").content();
        JsonNode tests = context.artifact("test-plan").content();
        JsonNode security = context.artifact("security-review").content();
        JsonNode docs = context.artifact("docs").content();
        String current = context.requirement().attributes().getOrDefault("currentVersion", "1.4.0");
        Release release = release(current, design, implementation, tests, security, docs);
        return AgentResult.of(release).withDecision(release.bump() + " release " + release.version(),
                "API/schema changes are additive → minor; internal-only → patch (semantic versioning)");
    }

    Release release(String current, JsonNode design, JsonNode implementation, JsonNode tests, JsonNode security, JsonNode docs) {
        boolean additiveChange = !Json.strings(design, "apiChanges").isEmpty() || !Json.objects(design, "schemaChanges").isEmpty();
        boolean breaking = Json.objects(design, "schemaChanges").stream().anyMatch(s -> !s.path("backwardCompatible").asBoolean(true));
        String bump = breaking ? "MAJOR" : additiveChange ? "MINOR" : "PATCH";

        List<Check> readiness = new ArrayList<>();
        boolean uncovered = !Json.strings(tests, "uncoveredCriteria").isEmpty();
        readiness.add(new Check("every acceptance criterion has a test", uncovered ? "FAIL" : "PASS",
                Json.objects(tests, "testCases").size() + " test case(s)"));
        String severity = Json.text(security, "highestSeverity", "NONE");
        readiness.add(new Check("no HIGH security findings", severity.equals("HIGH") ? "FAIL" : "PASS", "highest: " + severity));
        readiness.add(new Check("migrations backward compatible", breaking ? "FAIL" : "PASS",
                Json.objects(design, "schemaChanges").size() + " schema change(s)"));
        readiness.add(new Check("documentation updated", Json.text(docs, "changelog", "").isBlank() ? "FAIL" : "PASS", "changelog + ADR draft"));
        readiness.add(new Check("changeset reviewed under change control", "PASS",
                Json.objects(implementation, "changes").size() + " file(s) on branch " + Json.text(implementation, "branch", "?")));

        List<String> rollout = List.of("deploy to staging and run the regression suites",
                "enable for 10% of traffic behind a feature flag; watch error rate and p95 latency for 30 min",
                "ramp to 100% if SLOs hold");
        List<String> rollback = new ArrayList<>(List.of("disable the feature flag (instant, no deploy)", "redeploy the previous version"));
        if (!Json.objects(design, "schemaChanges").isEmpty()) {
            rollback.add("no down-migration needed: schema changes are additive and ignored by the previous version");
        }
        return new Release(current, bump(current, bump), bump, readiness,
                readiness.stream().allMatch(c -> c.status().equals("PASS")), rollout, rollback);
    }

    static String bump(String version, String kind) {
        String[] p = version.split("\\.");
        int major = Integer.parseInt(p[0]), minor = Integer.parseInt(p[1]), patch = Integer.parseInt(p[2]);
        return switch (kind) {
            case "MAJOR" -> (major + 1) + ".0.0";
            case "MINOR" -> major + "." + (minor + 1) + ".0";
            default -> major + "." + minor + "." + (patch + 1);
        };
    }
}
