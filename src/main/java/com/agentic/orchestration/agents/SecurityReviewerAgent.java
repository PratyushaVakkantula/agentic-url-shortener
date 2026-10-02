package com.agentic.orchestration.agents;

import com.agentic.orchestration.agent.Agent;
import com.agentic.orchestration.agent.AgentResult;
import com.agentic.orchestration.agent.StageContext;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import tools.jackson.databind.JsonNode;

/**
 * Design-level security review (runs in parallel with test planning). A rule-based checklist over
 * the design and the proposed changeset: input validation for changed write APIs, authorization
 * for new endpoints, migration safety, security-sensitive files, sensitive data in scope.
 *
 * <p>Complements the policy engine: policies scan <i>content</i> for leaks; this reviews the
 * <i>design</i> for missing controls. Any HIGH finding blocks the release stage's entry gate.
 */
public class SecurityReviewerAgent implements Agent {

    private static final Pattern SENSITIVE = Pattern.compile("\\b(password|token|secret|personal|email|address|payment|card)\\b", Pattern.CASE_INSENSITIVE);

    public record Finding(String id, String severity, String area, String finding, String recommendation) {
    }

    public record Review(List<Finding> findings, String highestSeverity, boolean approved) {
    }

    @Override
    public String name() {
        return "security-reviewer";
    }

    @Override
    public AgentResult execute(StageContext context) {
        JsonNode requirements = context.artifact("requirements").content();
        JsonNode design = context.artifact("design").content();
        JsonNode implementation = context.artifact("implementation").content();
        Review review = review(requirements, design, implementation);
        return AgentResult.of(review).withDecision("security review: highest severity " + review.highestSeverity(),
                review.findings().size() + " finding(s) from the design/changeset checklist");
    }

    Review review(JsonNode requirements, JsonNode design, JsonNode implementation) {
        List<Finding> findings = new ArrayList<>();
        for (String api : Json.strings(design, "apiChanges")) {
            if (api.startsWith("POST") || api.startsWith("PUT") || api.startsWith("PATCH")) {
                findings.add(finding(findings, "MEDIUM", "input-validation", "write endpoint changes: " + api,
                        "bean-validate the new field with explicit bounds; reject unknown values with a stable error code"));
            }
            if (api.contains(": create") || api.contains(": read")) {
                findings.add(finding(findings, "MEDIUM", "authorization", "new endpoint without a stated access rule: " + api,
                        "add a SecurityConfig rule (deny by default) and a test for anonymous/forbidden access"));
            }
        }
        for (JsonNode schema : Json.objects(design, "schemaChanges")) {
            boolean compatible = schema.path("backwardCompatible").asBoolean(false);
            findings.add(finding(findings, compatible ? "LOW" : "HIGH", "migration",
                    schema.path("migration").asString() + (compatible ? " is additive" : " is not backward compatible"),
                    compatible ? "no action; keep the column nullable until all writers set it"
                            : "split into expand/contract migrations across releases"));
        }
        for (JsonNode change : Json.objects(implementation, "changes")) {
            String path = change.path("path").asString();
            if (path.contains("/security/") || path.contains("SecurityConfig")) {
                findings.add(finding(findings, "HIGH", "security-config", "changeset modifies " + path,
                        "requires review by the security owner before merge"));
            }
        }
        if (SENSITIVE.matcher(Json.text(requirements, "statement", "") + " " + Json.text(requirements, "title", "")).find()) {
            findings.add(finding(findings, "MEDIUM", "data-protection", "requirement involves sensitive data",
                    "classify the data, avoid logging it, encrypt at rest if persisted"));
        }
        String highest = findings.stream().map(Finding::severity)
                .reduce("NONE", (a, b) -> rank(b) > rank(a) ? b : a);
        return new Review(findings, highest, !highest.equals("HIGH"));
    }

    private static Finding finding(List<Finding> existing, String severity, String area, String finding, String recommendation) {
        return new Finding("SEC-" + (existing.size() + 1), severity, area, finding, recommendation);
    }

    private static int rank(String severity) {
        return switch (severity) {
            case "HIGH" -> 3;
            case "MEDIUM" -> 2;
            case "LOW" -> 1;
            default -> 0;
        };
    }
}
