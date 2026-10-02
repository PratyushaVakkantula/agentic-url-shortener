package com.agentic.orchestration.governance;

import java.util.ArrayList;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

/**
 * CHANGE_CONTROL: inspects proposed changesets, by convention an output field {@code changes}
 * of {@code [{path, operation: ADD|MODIFY|DELETE, linesChanged}]}.
 *
 * <ul>
 *   <li>Editing or deleting an existing DB migration: BLOCK. Applied migrations are immutable
 *       (the same rule this repository follows; see V3).</li>
 *   <li>Adding a migration, or touching security configuration: REQUIRE_APPROVAL.</li>
 *   <li>Large blast radius (too many files or lines): REQUIRE_APPROVAL.</li>
 * </ul>
 */
@Component
public class ChangeControlPolicy implements Policy {

    private final int maxFiles;
    private final int maxLines;

    public ChangeControlPolicy(@Value("${app.orchestration.change-control.max-files:15}") int maxFiles,
                               @Value("${app.orchestration.change-control.max-lines:800}") int maxLines) {
        this.maxFiles = maxFiles;
        this.maxLines = maxLines;
    }

    @Override
    public String name() {
        return "change-control";
    }

    @Override
    public PolicyCategory category() {
        return PolicyCategory.CHANGE_CONTROL;
    }

    @Override
    public PolicyDecision evaluate(PolicyInput input) {
        JsonNode changes = input.output() == null ? null : input.output().get("changes");
        if (changes == null || !changes.isArray() || changes.isEmpty()) {
            return PolicyDecision.allow(this);
        }
        List<String> blocks = new ArrayList<>();
        List<String> reviews = new ArrayList<>();
        int lines = 0;
        for (JsonNode change : changes) {
            String path = change.path("path").asString("");
            String op = change.path("operation").asString("MODIFY");
            lines += change.path("linesChanged").asInt(0);
            boolean migration = path.contains("db/migration/");
            if (migration && !op.equals("ADD")) {
                blocks.add(op + " of applied migration " + path);
            } else if (migration) {
                reviews.add("schema change " + path);
            }
            if (path.contains("/security/") || path.endsWith("SecurityConfig.java")) {
                reviews.add("security configuration " + path);
            }
        }
        if (changes.size() > maxFiles) {
            reviews.add(changes.size() + " files changed (limit " + maxFiles + ")");
        }
        if (lines > maxLines) {
            reviews.add(lines + " lines changed (limit " + maxLines + ")");
        }
        if (!blocks.isEmpty()) {
            return new PolicyDecision(name(), category(), PolicyOutcome.BLOCK, String.join("; ", blocks));
        }
        if (!reviews.isEmpty()) {
            return new PolicyDecision(name(), category(), PolicyOutcome.REQUIRE_APPROVAL, String.join("; ", reviews));
        }
        return PolicyDecision.allow(this);
    }
}
