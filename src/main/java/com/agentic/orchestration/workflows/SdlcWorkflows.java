package com.agentic.orchestration.workflows;

import com.agentic.orchestration.agents.ArchitectAgent;
import com.agentic.orchestration.agents.ClarificationAgent;
import com.agentic.orchestration.agents.ImpactAnalysisAgent;
import com.agentic.orchestration.agents.ImpactChecklistAgent;
import com.agentic.orchestration.agents.ImplementationPlannerAgent;
import com.agentic.orchestration.agents.ReleaseManagerAgent;
import com.agentic.orchestration.agents.RequirementsAnalystAgent;
import com.agentic.orchestration.agents.SecurityReviewerAgent;
import com.agentic.orchestration.agents.TechWriterAgent;
import com.agentic.orchestration.agents.TestPlannerAgent;
import com.agentic.orchestration.definition.Gate;
import com.agentic.orchestration.definition.Gates;
import com.agentic.orchestration.definition.WorkflowDefinition;
import java.nio.file.Path;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.JsonNode;

/**
 * The three SDLC workflows required by the brief. They share the delivery pipeline and differ in
 * what happens before design:
 *
 * <pre>
 * greenfield-feature:    requirements ────────────────────────────────────▶ design ─▶ implementation ─┬▶ test-plan ───────┬▶ docs ─▶ release*
 * brownfield-change:     requirements ─────────────────▶ impact-analysis ─▶ design ─▶ implementation ─┤                  │
 * ambiguous-requirement: requirements ─▶ clarification* ─▶ impact-analysis ─▶ design ─▶ implementation ─┴▶ security-review ┘
 *                                                                              * = human approval checkpoint
 * </pre>
 *
 * Policy guardrails add further checkpoints dynamically (e.g. implementation with a migration).
 */
@Configuration
public class SdlcWorkflows {

    public static final String GREENFIELD = "greenfield-feature";
    public static final String BROWNFIELD = "brownfield-change";
    public static final String AMBIGUOUS = "ambiguous-requirement";

    private static final Duration STAGE_TIMEOUT = Duration.ofSeconds(60);

    private final Path codebaseRoot;
    private final String basePackage;

    public SdlcWorkflows(@Value("${app.orchestration.codebase-root:.}") String codebaseRoot,
                         @Value("${app.orchestration.base-package:com.agentic}") String basePackage) {
        this.codebaseRoot = Path.of(codebaseRoot);
        this.basePackage = basePackage;
    }

    @Bean
    WorkflowDefinition greenfieldWorkflow() {
        var builder = WorkflowDefinition.builder(GREENFIELD, 1);
        requirements(builder);
        return delivery(builder, "requirements").build();
    }

    @Bean
    WorkflowDefinition brownfieldWorkflow() {
        var builder = WorkflowDefinition.builder(BROWNFIELD, 1);
        requirements(builder);
        impactAnalysis(builder, "requirements");
        return delivery(builder, "impact-analysis").build();
    }

    @Bean
    WorkflowDefinition ambiguousWorkflow() {
        var builder = WorkflowDefinition.builder(AMBIGUOUS, 1);
        requirements(builder);
        builder.stage("clarification", new ClarificationAgent())
                .description("Open questions and proposed assumptions for a human to confirm or correct")
                .dependsOn("requirements")
                .requiresApproval("ambiguous requirement: confirm the assumptions, or revise the requirements artifact")
                .timeout(STAGE_TIMEOUT)
                .add();
        // An ambiguous requirement usually targets the existing system, so it gets codebase reasoning too,
        // but only after a human has confirmed what is actually being asked.
        impactAnalysis(builder, "clarification");
        return delivery(builder, "impact-analysis").build();
    }

    private void impactAnalysis(WorkflowDefinition.Builder builder, String after) {
        builder.stage("impact-analysis", new ImpactAnalysisAgent(codebaseRoot, basePackage))
                .description("Static analysis of the existing codebase: affected modules, APIs, tables, tests")
                .dependsOn(after)
                .entryGate(Gates.upstreamHas("requirements", "keywords"))
                .exitGate(Gates.outputHas("riskLevel"))
                .retry(2, Duration.ofMillis(200))
                .timeout(STAGE_TIMEOUT)
                .fallback(new ImpactChecklistAgent())
                .add();
    }

    private static void requirements(WorkflowDefinition.Builder builder) {
        builder.stage("requirements", new RequirementsAnalystAgent())
                .description("Normalise the requirement: acceptance criteria, ambiguities, assumptions")
                .exitGate(Gates.outputHas("acceptanceCriteria"))
                .timeout(STAGE_TIMEOUT)
                .add();
    }

    /** design → implementation → (test-plan ∥ security-review) → docs → release. */
    private static WorkflowDefinition.Builder delivery(WorkflowDefinition.Builder builder, String designAfter) {
        return builder
                .stage("design", new ArchitectAgent())
                .description("Components, API and schema changes, design decisions")
                .dependsOn(designAfter)
                .entryGate(Gates.upstreamHas("requirements", "acceptanceCriteria"))
                .exitGate(Gates.outputHas("components"))
                .retry(2, Duration.ofMillis(200)).timeout(STAGE_TIMEOUT)
                .add()
                .stage("implementation", new ImplementationPlannerAgent())
                .description("Proposed changeset and ordered task plan (never applied by the agent)")
                .dependsOn("design")
                .entryGate(Gates.upstreamHas("design", "components"))
                .exitGate(Gates.outputHas("changes"))
                .retry(2, Duration.ofMillis(200)).timeout(STAGE_TIMEOUT)
                .compensation(ctx -> "discarded draft branch " + ctx.artifact().content().path("branch").asString())
                .add()
                .stage("test-plan", new TestPlannerAgent())
                .description("Test cases mapped to acceptance criteria, migration and contract tests, regressions")
                .dependsOn("implementation")
                .exitGate(Gates.outputField("uncoveredCriteria", JsonNode::isEmpty,
                        "some acceptance criteria cannot be proven by a test (vague and without an assumption)"))
                .timeout(STAGE_TIMEOUT)
                .add()
                .stage("security-review", new SecurityReviewerAgent())
                .description("Design and changeset security checklist")
                .dependsOn("implementation")
                .exitGate(Gates.outputHas("highestSeverity"))
                .timeout(STAGE_TIMEOUT)
                .add()
                .stage("docs", new TechWriterAgent())
                .description("Changelog, API docs, ADR draft")
                .dependsOn("test-plan", "security-review")
                .timeout(STAGE_TIMEOUT)
                .add()
                .stage("release", new ReleaseManagerAgent())
                .description("Version, readiness checklist, rollout and rollback plan")
                .dependsOn("docs")
                .entryGate(noHighSecurityFindings())
                .requiresApproval("production release")
                .timeout(STAGE_TIMEOUT)
                .compensation(ctx -> "revoked release candidate tag v" + ctx.artifact().content().path("version").asString() + "-rc")
                .add();
    }

    private static Gate noHighSecurityFindings() {
        return Gate.of("security:no-high-findings",
                in -> !"HIGH".equals(in.context().artifact("security-review").content().path("highestSeverity").asString()),
                "security review reported HIGH severity findings");
    }
}
