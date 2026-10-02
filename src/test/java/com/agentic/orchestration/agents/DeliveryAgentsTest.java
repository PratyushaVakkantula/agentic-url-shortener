package com.agentic.orchestration.agents;

import static org.assertj.core.api.Assertions.assertThat;

import com.agentic.orchestration.model.Requirement;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Rules of the design, planning, testing, security and release agents, on hand-made inputs. */
class DeliveryAgentsTest {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    private static JsonNode json(Object value) {
        return MAPPER.valueToTree(value);
    }

    private static JsonNode requirements(String title, String description) {
        return json(new RequirementsAnalystAgent().analyse(new Requirement(title, description)));
    }

    @Test
    void architectAddsNullableColumnThroughNextMigrationForNewPersistedData() {
        JsonNode req = requirements("Add per-link click limits",
                "Each link can have an optional maximum number of clicks. The link must stop redirecting when reached. The limit must be set when the link is created.");
        JsonNode impact = json(Map.of("primaryModules", List.of("shortener"), "tables", List.of("short_link"),
                "apis", List.of("POST /api/v1/urls", "GET /{code}"), "nextMigration", "V7",
                "impactedFiles", List.of("src/main/java/com/agentic/shortener/domain/ShortLink.java"),
                "seeds", List.of(Map.of("type", "ShortLink", "reason", "name matches")),
                "existingTests", List.of("ShortLinkTest"), "riskLevel", "MEDIUM"));

        var design = new ArchitectAgent().design(req, impact);

        assertThat(design.schemaChanges()).singleElement().satisfies(s -> {
            assertThat(s.migration()).startsWith("V7__add_click_limit_to_short_link");
            assertThat(s.backwardCompatible()).isTrue();
        });
        assertThat(design.apiChanges()).anyMatch(a -> a.startsWith("POST /api/v1/urls"))
                .anyMatch(a -> a.startsWith("GET /{code}") && a.contains("410"));
        assertThat(design.components()).anyMatch(c -> c.name().equals("ShortLink") && c.change().equals("MODIFY"));
    }

    @Test
    void assumptionsShapeTheDesignSoRevisingThemChangesIt() {
        var vague = new ArchitectAgent().design(requirements("Make it faster and more reliable", ""), null);
        var precise = new ArchitectAgent().design(requirements("Cap redirects", "Redirects must return within 50 ms."), null);

        assertThat(vague.components()).extracting(ArchitectAgent.Component::name).contains("LatencyBudget", "ResilienceGuards");
        assertThat(vague.basedOnAssumptions()).isNotEmpty();
        assertThat(precise.components()).extracting(ArchitectAgent.Component::name).doesNotContain("LatencyBudget");
    }

    @Test
    void implementationTasksFollowLayerDependencies() {
        JsonNode design = json(Map.of("components", List.of(
                Map.of("name", "ShortLinkController", "kind", "controller", "change", "MODIFY", "path", "a/Ctl.java", "reason", ""),
                Map.of("name", "V5__x.sql", "kind", "migration", "change", "ADD", "path", "db/migration/V5__x.sql", "reason", ""),
                Map.of("name", "ShortLink", "kind", "entity", "change", "MODIFY", "path", "a/ShortLink.java", "reason", ""))));

        var plan = new ImplementationPlannerAgent().plan(design, "12345678-aaaa");

        assertThat(plan.branch()).isEqualTo("agent/12345678");
        assertThat(plan.tasks()).extracting(ImplementationPlannerAgent.Task::title)
                .containsExactly("Add V5__x.sql (migration)", "Update ShortLink (entity)", "Update ShortLinkController (controller)");
        assertThat(plan.tasks().get(2).dependsOn()).containsExactly("T-1", "T-2");
        assertThat(plan.changes()).extracting(ImplementationPlannerAgent.Change::operation).containsExactly("ADD", "MODIFY", "MODIFY");
    }

    @Test
    void testPlannerRejectsCriteriaThatCannotBeProven() {
        JsonNode vagueWithoutAssumption = json(Map.of(
                "acceptanceCriteria", List.of(Map.of("id", "AC-1", "text", "The service should be fast")),
                "ambiguities", List.of(), "assumptions", List.of()));
        JsonNode vagueWithAssumption = requirements("Speed", "The service should be fast.");

        var uncovered = new TestPlannerAgent().plan(vagueWithoutAssumption, json(Map.of()), null);
        var covered = new TestPlannerAgent().plan(vagueWithAssumption, json(Map.of()), null);

        assertThat(uncovered.uncoveredCriteria()).singleElement().asString().contains("not measurable");
        assertThat(covered.uncoveredCriteria()).isEmpty();
        assertThat(covered.testCases().getFirst().type()).isEqualTo("load");
    }

    @Test
    void securityReviewFlagsBreakingMigrationAndSecurityConfigAsHigh() {
        JsonNode design = json(Map.of("apiChanges", List.of(),
                "schemaChanges", List.of(Map.of("migration", "V5__drop.sql", "backwardCompatible", false))));
        JsonNode impl = json(Map.of("changes", List.of(Map.of("path", "src/main/java/x/platform/security/SecurityConfig.java"))));

        var review = new SecurityReviewerAgent().review(json(Map.of("title", "t", "statement", "")), design, impl);

        assertThat(review.highestSeverity()).isEqualTo("HIGH");
        assertThat(review.approved()).isFalse();
        assertThat(review.findings()).extracting(SecurityReviewerAgent.Finding::area).contains("migration", "security-config");
    }

    @Test
    void releaseBumpsSemverByImpact() {
        assertThat(ReleaseManagerAgent.bump("1.4.2", "MINOR")).isEqualTo("1.5.0");
        assertThat(ReleaseManagerAgent.bump("1.4.2", "PATCH")).isEqualTo("1.4.3");
        assertThat(ReleaseManagerAgent.bump("1.4.2", "MAJOR")).isEqualTo("2.0.0");
    }
}
