package com.agentic.orchestration.agents;

import com.agentic.orchestration.agent.Agent;
import com.agentic.orchestration.agent.AgentResult;
import com.agentic.orchestration.agent.StageContext;
import java.util.ArrayList;
import java.util.List;
import tools.jackson.databind.JsonNode;

/** Documentation (core requirement 5): changelog entry, API notes, an ADR draft and a test summary. */
public class TechWriterAgent implements Agent {

    public record AdrDraft(String title, String context, String decision, String consequences) {
    }

    public record Docs(String changelog, List<String> apiDocs, AdrDraft adrDraft, String testingNotes) {
    }

    @Override
    public String name() {
        return "tech-writer";
    }

    @Override
    public AgentResult execute(StageContext context) {
        JsonNode requirements = context.artifact("requirements").content();
        JsonNode design = context.artifact("design").content();
        JsonNode tests = context.artifact("test-plan").content();
        JsonNode security = context.artifact("security-review").content();

        String title = Json.text(requirements, "title", "Change");
        List<String> decisions = new ArrayList<>();
        Json.objects(design, "decisions").forEach(d -> decisions.add(d.path("title").asString() + ": " + d.path("choice").asString()));
        Docs docs = new Docs(
                "### Added\n- " + title + "\n### Changed\n" + String.join("\n", Json.strings(design, "apiChanges").stream().map(a -> "- " + a).toList()),
                Json.strings(design, "apiChanges"),
                new AdrDraft(title, Json.text(requirements, "statement", ""), String.join("; ", decisions),
                        String.join("; ", Json.strings(design, "risks"))),
                Json.objects(tests, "testCases").size() + " planned test case(s); security review highest severity "
                        + Json.text(security, "highestSeverity", "NONE"));
        return AgentResult.of(docs).withDecision("documented " + docs.apiDocs().size() + " API change(s) and an ADR draft",
                "every user-visible change is reflected in the changelog and API docs");
    }
}
