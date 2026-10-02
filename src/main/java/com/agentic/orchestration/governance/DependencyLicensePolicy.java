package com.agentic.orchestration.governance;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

/**
 * COMPLIANCE: new third-party dependencies (output field {@code dependencies} of
 * {@code [{name, version, license}]}) must carry an approved license. Strong copyleft or
 * source-available licenses are blocked; unknown licenses go to a human.
 */
@Component
public class DependencyLicensePolicy implements Policy {

    static final Set<String> DENIED = Set.of("AGPL-3.0", "GPL-2.0", "GPL-3.0", "SSPL-1.0", "BUSL-1.1");
    static final Set<String> ALLOWED = Set.of("Apache-2.0", "MIT", "BSD-2-Clause", "BSD-3-Clause", "EPL-2.0", "MPL-2.0", "ISC");

    @Override
    public String name() {
        return "dependency-license";
    }

    @Override
    public PolicyCategory category() {
        return PolicyCategory.COMPLIANCE;
    }

    @Override
    public PolicyDecision evaluate(PolicyInput input) {
        JsonNode deps = input.output() == null ? null : input.output().get("dependencies");
        if (deps == null || !deps.isArray()) {
            return PolicyDecision.allow(this);
        }
        List<String> denied = new ArrayList<>();
        List<String> unknown = new ArrayList<>();
        for (JsonNode dep : deps) {
            String id = dep.path("name").asString("?") + ":" + dep.path("version").asString("?");
            String license = dep.path("license").asString("");
            if (DENIED.contains(license)) {
                denied.add(id + " (" + license + ")");
            } else if (!ALLOWED.contains(license)) {
                unknown.add(id + " (" + (license.isBlank() ? "no license" : license) + ")");
            }
        }
        if (!denied.isEmpty()) {
            return new PolicyDecision(name(), category(), PolicyOutcome.BLOCK, "disallowed license: " + String.join(", ", denied));
        }
        if (!unknown.isEmpty()) {
            return new PolicyDecision(name(), category(), PolicyOutcome.REQUIRE_APPROVAL,
                    "license needs legal review: " + String.join(", ", unknown));
        }
        return PolicyDecision.allow(this);
    }
}
