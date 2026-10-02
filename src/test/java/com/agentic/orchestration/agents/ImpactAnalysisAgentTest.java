package com.agentic.orchestration.agents;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.agentic.orchestration.agents.ImpactAnalysisAgent.Impact;
import com.agentic.orchestration.agents.ImpactAnalysisAgent.Seed;
import com.agentic.orchestration.model.Requirement;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Runs the real static analysis against <b>this</b> repository. Assertions use facts that are
 * stable by design (module boundaries, the link table, controller routes); anything that grows
 * over time (next migration number) is derived from the files rather than hard-coded.
 */
class ImpactAnalysisAgentTest {

    private static final Path ROOT = Path.of(".");
    private static CodebaseIndex index;

    @BeforeAll
    static void scan() {
        index = CodebaseIndex.scan(ROOT, "com.agentic");
    }

    private Impact analyse(String... keywords) {
        return new ImpactAnalysisAgent(ROOT, "com.agentic").analyse(index, List.of(keywords), null);
    }

    @Test
    void indexExtractsEndpointsTablesAndDependencies() throws Exception {
        var controller = index.types().stream().filter(t -> t.simpleName().equals("ShortLinkController")).findFirst().orElseThrow();
        assertThat(controller.module()).isEqualTo("shortener");
        assertThat(controller.endpoints()).contains("POST /api/v1/urls", "GET /api/v1/urls/{code}/analytics");

        var entity = index.types().stream().filter(t -> t.simpleName().equals("ShortLink")).findFirst().orElseThrow();
        assertThat(entity.table()).isEqualTo("short_link");

        String repository = "com.agentic.shortener.repository.ShortLinkRepository";
        assertThat(index.dependentsOf(repository)).contains("com.agentic.shortener.service.ShortLinkService");
        assertThat(index.dependenciesOf("com.agentic.shortener.service.RedirectService"))
                .as("same-package reference without an import").contains("com.agentic.shortener.service.RedirectCache");

        long migrationsOnDisk;
        try (Stream<Path> files = Files.list(ROOT.resolve("src/main/resources/db/migration"))) {
            migrationsOnDisk = files.count();
        }
        assertThat(index.nextMigrationVersion()).isEqualTo(migrationsOnDisk + 1);
    }

    @Test
    void clickLimitRequirementLandsInTheShortenerAndFindsTheLinkTable() {
        // Keywords exactly as the requirements analyst produces them in the pipeline.
        List<String> keywords = new RequirementsAnalystAgent().analyse(new Requirement("Add per-link click limits",
                "A link must stop redirecting once its maximum number of clicks is reached.")).keywords();
        Impact impact = new ImpactAnalysisAgent(ROOT, "com.agentic").analyse(index, keywords, null);

        assertThat(impact.primaryModules()).containsExactly("shortener");
        assertThat(impact.seeds()).extracting(Seed::type).contains("RedirectService");
        assertThat(impact.impactedClasses()).as("the entity the seeds operate on is included")
                .contains("ShortLink", "RedirectController", "ShortLinkService");
        assertThat(impact.modules()).as("'limit' must not drag in the rate limiter").containsExactly("shortener");
        assertThat(impact.tables()).as("central aggregate first").startsWith("short_link").contains("click_event");
        assertThat(impact.apis()).contains("POST /api/v1/urls");
        assertThat(impact.existingTests()).contains("ShortenerApiIntegrationTest");
        assertThat(impact.nextMigration()).isEqualTo("V" + index.nextMigrationVersion());
        assertThat(impact.riskLevel()).isEqualTo("MEDIUM");
    }

    @Test
    void rateLimitRequirementLandsInThePlatformModule() {
        Impact impact = analyse("rate", "limit", "token", "bucket", "throttle");

        assertThat(impact.primaryModules()).containsExactly("platform");
        assertThat(impact.seeds()).extracting(Seed::type).contains("TokenBucket", "RateLimitFilter");
        assertThat(impact.tables()).isEmpty();
    }

    @Test
    void missingSourceTreeFailsLoudlySoTheFallbackTakesOver() {
        assertThatThrownBy(() -> CodebaseIndex.scan(Path.of("/nonexistent"), "com.agentic"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("needs the repository checked out");
    }
}
