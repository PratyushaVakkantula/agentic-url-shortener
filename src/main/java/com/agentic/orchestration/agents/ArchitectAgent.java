package com.agentic.orchestration.agents;

import com.agentic.orchestration.agent.Agent;
import com.agentic.orchestration.agent.AgentResult;
import com.agentic.orchestration.agent.StageContext;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import tools.jackson.databind.JsonNode;

/**
 * Produces a design proposal (core requirement 5) from the requirements and, for brownfield work,
 * the impact analysis:
 * <ul>
 *   <li><b>Brownfield:</b> modify the impacted seed types; add a backward-compatible (nullable)
 *       column through the next migration when the requirement introduces persisted data;
 *       derive API changes from what the criteria say about creation and redirection.</li>
 *   <li><b>Greenfield:</b> a new module laid out in the house layers (domain, repository,
 *       service, api) with its first migration.</li>
 *   <li><b>Quality attributes</b> named by the requirement or its assumptions add concrete
 *       measures (e.g. performance → read-through cache and a latency budget), so revising an
 *       assumption visibly changes the design (and re-planning re-runs this agent).</li>
 * </ul>
 */
public class ArchitectAgent implements Agent {

    private static final Pattern NEW_DATA = Pattern.compile("\\b(optional|maximum|minimum|limit|store|persist|field|attribute|set when|configur)", Pattern.CASE_INSENSITIVE);
    private static final Pattern CREATE = Pattern.compile("\\b(creat|set when|on creation|submit)", Pattern.CASE_INSENSITIVE);
    private static final Pattern REDIRECT = Pattern.compile("\\b(redirect|410|gone|stop)", Pattern.CASE_INSENSITIVE);

    /** Quality attribute → (component, measure, rationale). */
    private static final Map<String, String[]> QUALITY_MEASURES = Map.of(
            "performance", new String[] {"LatencyBudget", "read-through cache on the hot path plus a p95 latency SLO alert",
                    "the assumed latency target is only met if the hot path avoids the database"},
            "reliability", new String[] {"ResilienceGuards", "timeouts on outbound calls, health probes, graceful degradation of non-critical features",
                    "availability target requires failures to degrade features rather than the service"},
            "scalability", new String[] {"CapacityLimits", "bounded queues and caches with explicit size limits",
                    "assumed peak load must not exhaust memory"},
            "security", new String[] {"InputHardening", "strict validation of every new input and abuse rate limits",
                    "threat model in scope covers public API abuse"},
            "usability", new String[] {"ErrorContract", "stable machine-readable error codes for every new failure mode",
                    "API consumers must be able to act on errors without reading logs"});

    public record Component(String name, String kind, String change, String path, String reason) {
    }

    public record SchemaChange(String migration, String statement, boolean backwardCompatible) {
    }

    public record DesignDecision(String title, String choice, String rationale) {
    }

    public record Design(String approach, List<Component> components, List<String> apiChanges,
                         List<SchemaChange> schemaChanges, List<DesignDecision> decisions, List<String> risks,
                         List<String> basedOnAssumptions) {
    }

    @Override
    public String name() {
        return "architect";
    }

    @Override
    public AgentResult execute(StageContext context) {
        JsonNode requirements = context.artifact("requirements").content();
        JsonNode impact = context.hasArtifact("impact-analysis") ? context.artifact("impact-analysis").content() : null;
        Design design = design(requirements, impact);
        AgentResult result = AgentResult.of(design);
        for (DesignDecision d : design.decisions()) {
            result = result.withDecision(d.title() + ": " + d.choice(), d.rationale());
        }
        return result;
    }

    Design design(JsonNode requirements, JsonNode impact) {
        List<String> criteria = Json.pluck(requirements, "acceptanceCriteria", "text");
        List<String> keywords = Json.strings(requirements, "keywords");
        String allCriteria = String.join(". ", criteria) + ". " + Json.text(requirements, "statement", "");

        List<Component> components = new ArrayList<>();
        List<String> apiChanges = new ArrayList<>();
        List<SchemaChange> schema = new ArrayList<>();
        List<DesignDecision> decisions = new ArrayList<>();
        List<String> risks = new ArrayList<>();
        String approach;

        boolean brownfield = impact != null && !Json.strings(impact, "impactedFiles").isEmpty();
        if (brownfield) {
            approach = "Extend existing module(s) " + Json.strings(impact, "primaryModules") + " in place";
            List<String> files = Json.strings(impact, "impactedFiles");
            for (JsonNode seed : Json.objects(impact, "seeds")) {
                String type = seed.path("type").asString();
                String path = files.stream().filter(f -> f.endsWith("/" + type + ".java")).findFirst().orElse(type);
                components.add(new Component(type, kindOf(path), "MODIFY", path, seed.path("reason").asString()));
            }
            List<String> tables = Json.strings(impact, "tables");
            if (!tables.isEmpty() && NEW_DATA.matcher(allCriteria).find()) {
                String table = tables.getFirst();
                Set<String> tableTokens = Text.identifierTokens(table);
                String column = keywords.stream().map(Text::stem).filter(k -> !tableTokens.contains(k))
                        .limit(2).reduce((a, b) -> a + "_" + b).orElse("new_value");
                String migration = Json.text(impact, "nextMigration", "V1") + "__add_" + column + "_to_" + table + ".sql";
                schema.add(new SchemaChange(migration, "ALTER TABLE " + table + " ADD COLUMN " + column + " BIGINT", true));
                components.add(new Component(migration, "migration", "ADD", "src/main/resources/db/migration/" + migration,
                        "persist the new attribute"));
                decisions.add(new DesignDecision("Schema evolution", "add nullable column " + column + " via " + migration,
                        "nullable means existing rows stay valid and the old code keeps working during rollout (expand/contract)"));
            }
            List<String> apis = Json.strings(impact, "apis");
            if (CREATE.matcher(allCriteria).find()) {
                apis.stream().filter(a -> a.startsWith("POST")).findFirst()
                        .ifPresent(a -> apiChanges.add(a + ": accept the new optional field (additive, backward compatible)"));
            }
            if (REDIRECT.matcher(allCriteria).find()) {
                apis.stream().filter(a -> a.startsWith("GET /{")).findFirst()
                        .ifPresent(a -> apiChanges.add(a + ": new 410 Gone outcome when the rule applies"));
            }
            if ("HIGH".equals(Json.text(impact, "riskLevel", ""))) {
                risks.add("impact analysis rates this change HIGH risk: " + Json.text(impact, "riskRationale", ""));
            }
            risks.add("existing tests " + Json.strings(impact, "existingTests") + " must keep passing (regression)");
        } else {
            // Name after the first two keywords, e.g. "QR codes for short links" → module qrcode, entity QrCode.
            String phrase = keywords.isEmpty() ? "feature" : String.join("_", keywords.stream().limit(2).map(Text::stem).toList());
            String module = phrase.replace("_", "");
            String pkg = "src/main/java/com/agentic/" + module;
            String entity = Text.pascal(phrase);
            approach = "New module '" + module + "' following the existing layering (domain → repository → service → api)";
            components.add(new Component(entity, "entity", "ADD", pkg + "/domain/" + entity + ".java", "core aggregate"));
            components.add(new Component(entity + "Repository", "repository", "ADD", pkg + "/repository/" + entity + "Repository.java", "persistence"));
            components.add(new Component(entity + "Service", "service", "ADD", pkg + "/service/" + entity + "Service.java", "business rules"));
            components.add(new Component(entity + "Controller", "controller", "ADD", pkg + "/api/" + entity + "Controller.java", "REST API"));
            String migration = "V1__create_" + module + "_tables.sql";
            components.add(new Component(migration, "migration", "ADD", "src/main/resources/db/migration/" + migration, "initial schema"));
            schema.add(new SchemaChange(migration, "CREATE TABLE " + module + " (...)", true));
            apiChanges.add("POST /api/v1/" + module + "s: create");
            apiChanges.add("GET /api/v1/" + module + "s/{id}: read");
            decisions.add(new DesignDecision("Module boundary", "new top-level module '" + module + "'",
                    "keeps the feature independently testable; enforced by architecture rules"));
        }

        Set<String> qualities = new LinkedHashSet<>(Json.pluck(requirements, "ambiguities", "quality"));
        List<String> basedOn = Json.pluck(requirements, "assumptions", "id");
        for (String quality : qualities) {
            String[] measure = QUALITY_MEASURES.get(quality.toLowerCase(Locale.ROOT));
            if (measure != null) {
                components.add(new Component(measure[0], "cross-cutting", "ADD", "(see design)", measure[1]));
                decisions.add(new DesignDecision("Quality: " + quality, measure[1], measure[2]));
            }
        }
        if (!basedOn.isEmpty()) {
            risks.add("design rests on unconfirmed assumptions " + basedOn + "; revising them re-plans this design");
        }
        return new Design(approach, components, apiChanges, schema, decisions, risks, basedOn);
    }

    private static String kindOf(String path) {
        if (path.contains("/api/")) {
            return path.endsWith("Controller.java") ? "controller" : "dto";
        }
        if (path.contains("/domain/")) {
            return "entity";
        }
        if (path.contains("/repository/")) {
            return "repository";
        }
        if (path.contains("/service/")) {
            return "service";
        }
        return "other";
    }
}
