package com.agentic.orchestration.agents;

import static org.assertj.core.api.Assertions.assertThat;

import com.agentic.orchestration.agents.RequirementsAnalystAgent.Analysis;
import com.agentic.orchestration.agents.RequirementsAnalystAgent.Criterion;
import com.agentic.orchestration.model.Requirement;
import org.junit.jupiter.api.Test;

class RequirementsAnalystAgentTest {

    private final RequirementsAnalystAgent analyst = new RequirementsAnalystAgent();

    @Test
    void wellDefinedRequirementYieldsCriteriaAndNoQuestions() {
        Analysis a = analyst.analyse(new Requirement("Add per-link click limits",
                "Each short link can have an optional maximum number of clicks. Once the limit is reached, "
                        + "the link must stop redirecting and return 410 Gone. The limit must be set when the link is created."));

        assertThat(a.classification()).isEqualTo("WELL_DEFINED");
        assertThat(a.ambiguities()).isEmpty();
        assertThat(a.acceptanceCriteria()).extracting(Criterion::id).containsExactly("AC-1", "AC-2", "AC-3");
        assertThat(a.acceptanceCriteria().get(1).measurable()).as("mentions 410").isTrue();
        assertThat(a.keywords()).contains("link", "click", "limit", "redirect");
    }

    @Test
    void vagueRequirementIsFlaggedWithOneQuestionAndOneAssumptionPerAmbiguity() {
        Analysis a = analyst.analyse(new Requirement("Make the URL shortener faster and more reliable", ""));

        assertThat(a.classification()).isEqualTo("AMBIGUOUS");
        assertThat(a.needsClarification()).isTrue();
        assertThat(a.ambiguities()).extracting(RequirementsAnalystAgent.Ambiguity::quality)
                .containsExactly("performance", "reliability", "acceptance-criteria");
        assertThat(a.ambiguities().getFirst().term()).isEqualTo("faster");
        assertThat(a.assumptions()).hasSameSizeAs(a.ambiguities());
        assertThat(a.assumptions().getFirst().statement()).contains("p95").contains("50 ms");
        assertThat(a.assumptions().getFirst().resolves()).isEqualTo("Q-1");
        assertThat(a.clarityScore()).isLessThan(RequirementsAnalystAgent.AMBIGUITY_THRESHOLD);
    }

    @Test
    void classifiesChangeTypeFromTitleAndExplicitBugWordsOnly() {
        assertThat(analyst.analyse(new Requirement("Add per-link click limits", "Links must return 410 once the limit is reached.")).changeType())
                .isEqualTo("FEATURE");
        assertThat(analyst.analyse(new Requirement("Fix: one invalid click loses the whole analytics batch", "Valid clicks must still be recorded.")).changeType())
                .isEqualTo("BUG_FIX");
        assertThat(analyst.analyse(new Requirement("Analytics totals", "This regression appeared after the last release.")).changeType())
                .isEqualTo("BUG_FIX");
        assertThat(analyst.analyse(new Requirement("Retry webhooks", "If a delivery fails it must be retried three times.")).changeType())
                .as("a feature that mentions failure is still a feature").isEqualTo("FEATURE");
        assertThat(analyst.analyse(new Requirement("Refactor the click pipeline", "Split the writer.")).changeType())
                .isEqualTo("REFACTOR");
    }

    @Test
    void acronymsSurviveKeywordExtractionDespiteBeingShort() {
        assertThat(analyst.analyse(new Requirement("QR codes for short links", "The API must return a PNG.")).keywords())
                .startsWith("qr", "code").contains("api", "png");
    }

    @Test
    void eachQualityIsAskedAboutOnceEvenWhenMentionedRepeatedly() {
        Analysis a = analyst.analyse(new Requirement("Speed", "It must be fast. Really quick. Performant under load."));
        assertThat(a.ambiguities()).filteredOn(x -> x.quality().equals("performance")).hasSize(1);
    }
}
