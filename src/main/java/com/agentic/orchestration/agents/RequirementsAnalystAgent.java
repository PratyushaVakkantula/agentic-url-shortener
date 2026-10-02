package com.agentic.orchestration.agents;

import com.agentic.orchestration.agent.Agent;
import com.agentic.orchestration.agent.AgentResult;
import com.agentic.orchestration.agent.StageContext;
import com.agentic.orchestration.model.Requirement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Turns a free-text requirement into a normalised engineering problem (core requirement 1):
 * acceptance criteria, detected ambiguities with clarifying questions, explicit assumptions for
 * each ambiguity, keywords for downstream agents, and a clarity classification.
 *
 * <p>It does <b>not</b> guess greenfield vs brownfield: that depends on the codebase, which this
 * agent deliberately does not inspect. The caller chooses the workflow explicitly, and the
 * brownfield workflow's impact-analysis stage is what actually reasons about existing code.
 *
 * <p>Ambiguity detection is rule-based: a dictionary of vague qualities (each with a question and
 * a concrete default assumption), unmeasurable comparatives, and missing acceptance criteria.
 * Rules beat a model here: deterministic, explainable, and each finding names the exact phrase.
 */
public class RequirementsAnalystAgent implements Agent {

    /** Vague term → (clarifying question, default assumption that makes it testable). */
    record Vagueness(Pattern pattern, String quality, String question, String assumption) {
    }

    static final List<Vagueness> VAGUE_TERMS = List.of(
            vague("fast(er)?|quick(er|ly)?|performant|speedy|snappy|low[- ]latency", "performance",
                    "What latency target applies (e.g. p95 in ms) and at what request rate?",
                    "Redirect p95 latency <= 50 ms at 200 requests/s on a single instance"),
            vague("reliab(le|ility)|robust|resilient|highly available|stable", "reliability",
                    "What availability target applies, and which failures must be tolerated?",
                    "99.9% monthly availability; no data loss for created links; degraded analytics acceptable during DB slowness"),
            vague("scal(able|e|ability)", "scalability",
                    "What peak traffic and data volume must be supported?",
                    "Up to 10 million links and 1,000 redirects/s; horizontal scaling not required in this iteration"),
            vague("secure|security|safe(r|ly)?", "security",
                    "Which threats are in scope (abuse, data exposure, account takeover)?",
                    "OWASP Top 10 for the public API, plus abuse via malicious target URLs"),
            vague("user[- ]friendly|easy|simple|intuitive", "usability",
                    "Who are the users, and which tasks must be easy?",
                    "Primary users are API consumers; every error response is self-explanatory with a stable error code"),
            vague("better|improv(e|ed|ement)|optimi[sz]e(d)?|enhance(d)?", "improvement",
                    "Improve which metric, from what baseline, to what target?",
                    "Baseline measured before the change; success = measurable improvement of the named quality"),
            vague("many|several|some|various|a lot of|lots of|etc", "quantity",
                    "How many exactly, or what is the upper bound?",
                    "Bounded by an explicit, configurable limit documented in the design"),
            vague("appropriate(ly)?|proper(ly)?|adequate(ly)?|as needed|reasonable", "unspecified-behaviour",
                    "What concrete behaviour is expected here?",
                    "Behaviour follows existing conventions in the codebase and is documented"));

    private static final Pattern CRITERION = Pattern.compile("\\b(must|should|shall|needs? to|has to|will|can)\\b", Pattern.CASE_INSENSITIVE);
    static final double AMBIGUITY_THRESHOLD = 0.7;
    private static final Pattern MEASURABLE = Pattern.compile("\\d|\\b(all|every|none|never|always|only|exactly)\\b", Pattern.CASE_INSENSITIVE);

    private static Vagueness vague(String regex, String quality, String question, String assumption) {
        return new Vagueness(Pattern.compile("\\b(" + regex + ")\\b", Pattern.CASE_INSENSITIVE), quality, question, assumption);
    }

    public record Criterion(String id, String text, boolean measurable) {
    }

    public record Ambiguity(String id, String term, String quality, String excerpt, String question) {
    }

    public record Assumption(String id, String statement, String resolves) {
    }

    /** @param classification WELL_DEFINED or AMBIGUOUS (clarity below threshold) */
    public record Analysis(String title, String statement, String classification, double clarityScore,
                           boolean needsClarification, List<Criterion> acceptanceCriteria,
                           List<Ambiguity> ambiguities, List<Assumption> assumptions, List<String> keywords) {
    }

    @Override
    public String name() {
        return "requirements-analyst";
    }

    @Override
    public AgentResult execute(StageContext context) {
        Analysis analysis = analyse(context.requirement());
        AgentResult result = AgentResult.of(analysis).withDecision(
                "classified requirement as " + analysis.classification(),
                "clarity " + analysis.clarityScore() + ", " + analysis.ambiguities().size() + " ambiguity(ies), "
                        + analysis.acceptanceCriteria().size() + " acceptance criterion(a)");
        for (Assumption a : analysis.assumptions()) {
            result = result.withDecision("assumed: " + a.statement(), "resolves " + a.resolves() + " until a human confirms or corrects it");
        }
        return result;
    }

    Analysis analyse(Requirement requirement) {
        String fullText = requirement.title() + ". " + requirement.description();

        List<Criterion> criteria = new ArrayList<>();
        for (String sentence : Text.sentences(requirement.description())) {
            if (CRITERION.matcher(sentence).find()) {
                criteria.add(new Criterion("AC-" + (criteria.size() + 1), sentence, MEASURABLE.matcher(sentence).find()));
            }
        }

        List<Ambiguity> ambiguities = new ArrayList<>();
        List<Assumption> assumptions = new ArrayList<>();
        Map<String, Boolean> seenQualities = new LinkedHashMap<>();
        for (Vagueness v : VAGUE_TERMS) {
            Matcher m = v.pattern().matcher(fullText);
            if (m.find() && seenQualities.putIfAbsent(v.quality(), true) == null) {
                String id = "Q-" + (ambiguities.size() + 1);
                ambiguities.add(new Ambiguity(id, m.group(1).toLowerCase(Locale.ROOT), v.quality(), excerpt(fullText, m.start()), v.question()));
                assumptions.add(new Assumption("A-" + (assumptions.size() + 1), v.assumption(), id));
            }
        }
        if (criteria.isEmpty()) {
            String id = "Q-" + (ambiguities.size() + 1);
            ambiguities.add(new Ambiguity(id, "(none)", "acceptance-criteria", requirement.title(),
                    "What observable behaviour proves this is done? No 'must/should' statements were found."));
            assumptions.add(new Assumption("A-" + (assumptions.size() + 1),
                    "Done when: " + requirement.title() + " (verified by an end-to-end test)", id));
            criteria.add(new Criterion("AC-1", requirement.title(), false));
        }

        long unmeasurable = criteria.stream().filter(c -> !c.measurable()).count();
        double clarity = Math.max(0, 1.0 - 0.15 * ambiguities.size() - 0.05 * unmeasurable);
        clarity = Math.round(clarity * 100) / 100.0;
        boolean needsClarification = !ambiguities.isEmpty();

        String classification = needsClarification && clarity < AMBIGUITY_THRESHOLD ? "AMBIGUOUS" : "WELL_DEFINED";

        return new Analysis(requirement.title(), requirement.description(), classification, clarity, needsClarification,
                criteria, ambiguities, assumptions, Text.keywords(fullText));
    }

    private static String excerpt(String text, int at) {
        int start = Math.max(0, at - 30);
        int end = Math.min(text.length(), at + 40);
        return (start > 0 ? "…" : "") + text.substring(start, end).strip() + (end < text.length() ? "…" : "");
    }
}
