package com.agentic.orchestration.agents;

import com.agentic.orchestration.agent.Agent;
import com.agentic.orchestration.agent.AgentResult;
import com.agentic.orchestration.agent.StageContext;
import com.agentic.orchestration.agents.CodebaseIndex.JavaType;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import tools.jackson.databind.JsonNode;

/**
 * Brownfield codebase reasoning (core requirement 3, OR-17), done with real static analysis of
 * the repository the service runs from:
 * <ol>
 *   <li>Index the source tree ({@link CodebaseIndex}).</li>
 *   <li>Score every type against the requirement's keywords (name tokens weigh more than body
 *       mentions) and pick the most relevant <b>module</b>, then the top seed types inside it.
 *       Restricting to one module avoids false positives such as "limit" matching rate limiting.</li>
 *   <li>Expand seeds to their blast radius over the reverse-dependency graph, plus the
 *       persistent entities the seeds operate on.</li>
 *   <li>Trace <b>data flows</b>: from each impacted entry point (REST controller or background
 *       worker) through services to the repositories and tables it reaches.</li>
 *   <li>Report impacted modules, REST endpoints, tables, existing tests and the next migration.</li>
 * </ol>
 */
public class ImpactAnalysisAgent implements Agent {

    private static final int MAX_SEEDS = 5;
    private static final int RADIUS = 2;

    private final Path codebaseRoot;
    private final String basePackage;

    public ImpactAnalysisAgent(Path codebaseRoot, String basePackage) {
        this.codebaseRoot = codebaseRoot;
        this.basePackage = basePackage;
    }

    public record Seed(String type, int score, String reason) {
    }

    /**
     * One path from an entry point to persistent data, e.g.
     * {@code ShortLinkController → ShortLinkService → ShortLinkRepository → short_link}.
     *
     * @param entry     the entry type (controller or background worker)
     * @param endpoints its HTTP routes, or {@code background} for workers
     * @param path      types from entry to repository, by simple name
     */
    public record DataFlow(String entry, List<String> endpoints, List<String> path, String table) {
    }

    /** @param tables impacted tables, most central entity first */
    public record Impact(List<Seed> seeds, List<String> primaryModules, List<String> impactedClasses,
                         List<String> impactedFiles, List<String> modules, List<String> apis, List<DataFlow> dataFlows,
                         List<String> tables,
                         List<String> existingTests, String nextMigration, String riskLevel, String riskRationale,
                         Map<String, Integer> stats) {
    }

    @Override
    public String name() {
        return "impact-analyzer";
    }

    @Override
    public AgentResult execute(StageContext context) {
        JsonNode requirements = context.artifact("requirements").content();
        List<String> keywords = Json.strings(requirements, "keywords");
        String targetModule = context.requirement().attributes().get("targetModule");

        CodebaseIndex index = CodebaseIndex.scan(codebaseRoot, basePackage);
        Impact impact = analyse(index, keywords, targetModule);

        return AgentResult.of(impact)
                .withDecision("focus on module(s) " + impact.primaryModules(),
                        targetModule != null ? "requested via targetModule attribute"
                                : "highest keyword relevance across " + index.types().size() + " types")
                .withDecision("risk " + impact.riskLevel(), impact.riskRationale());
    }

    Impact analyse(CodebaseIndex index, List<String> keywords, String targetModule) {
        Set<String> wanted = new LinkedHashSet<>();
        keywords.forEach(k -> wanted.add(Text.stem(k)));

        Map<String, Integer> scores = new HashMap<>();
        Map<String, String> reasons = new HashMap<>();
        for (JavaType t : index.types()) {
            Set<String> nameTokens = new LinkedHashSet<>(Text.identifierTokens(t.simpleName()));
            if (t.table() != null) {
                nameTokens.addAll(Text.identifierTokens(t.table()));
            }
            t.endpoints().forEach(e -> nameTokens.addAll(Text.identifierTokens(e)));
            Set<String> nameHits = new TreeSet<>(nameTokens);
            nameHits.retainAll(wanted);

            String body = t.source().toLowerCase(Locale.ROOT);
            int bodyHits = 0;
            for (String w : wanted) {
                bodyHits += Math.min(5, countOccurrences(body, w));
            }
            int score = 10 * nameHits.size() + bodyHits;
            if (score > 0) {
                scores.put(t.fqn(), score);
                reasons.put(t.fqn(), (nameHits.isEmpty() ? "" : "name matches " + nameHits + "; ")
                        + bodyHits + " keyword mention(s) in source");
            }
        }

        List<String> primaryModules;
        if (targetModule != null) {
            primaryModules = List.of(targetModule);
        } else {
            Map<String, Integer> moduleScore = new HashMap<>();
            scores.forEach((fqn, s) -> moduleScore.merge(index.type(fqn).module(), s, Integer::sum));
            primaryModules = moduleScore.entrySet().stream()
                    .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
                    .limit(1).map(Map.Entry::getKey).toList();
        }

        List<String> seedFqns = scores.entrySet().stream()
                .filter(e -> primaryModules.contains(index.type(e.getKey()).module()))
                .sorted(Map.Entry.<String, Integer>comparingByValue().reversed().thenComparing(Map.Entry.comparingByKey()))
                .limit(MAX_SEEDS)
                .map(Map.Entry::getKey)
                .toList();
        List<Seed> seeds = seedFqns.stream()
                .map(fqn -> new Seed(index.type(fqn).simpleName(), scores.get(fqn), reasons.get(fqn)))
                .toList();

        Set<String> impacted = new LinkedHashSet<>(index.blastRadius(seedFqns, RADIUS));
        // Upstream too, but only for persistent entities: changing behaviour usually means changing the
        // data it operates on, and the reverse-dependency walk alone would never reach the entity.
        for (String seed : seedFqns) {
            index.dependenciesOf(seed).stream()
                    .filter(dep -> index.type(dep).annotations().contains("Entity"))
                    .forEach(impacted::add);
        }
        List<JavaType> impactedTypes = impacted.stream().map(index::type)
                .sorted(Comparator.comparing(JavaType::fqn)).toList();

        Set<String> modules = new TreeSet<>();
        Set<String> apis = new TreeSet<>();
        Map<String, Integer> tableCentrality = new HashMap<>();
        Set<String> tests = new TreeSet<>();
        for (JavaType t : impactedTypes) {
            modules.add(t.module());
            apis.addAll(t.endpoints());
            if (t.table() != null) {
                tableCentrality.put(t.table(), index.dependentsOf(t.fqn()).size());
            }
            tests.addAll(index.testsCovering(t.fqn()));
        }

        // Most central entity first: the aggregate most of the code depends on is where new
        // attributes usually belong (e.g. a per-link setting goes on short_link, not click_event).
        List<String> tables = tableCentrality.entrySet().stream()
                .sorted(Map.Entry.<String, Integer>comparingByValue().reversed().thenComparing(Map.Entry.comparingByKey()))
                .map(Map.Entry::getKey).toList();

        List<DataFlow> flows = dataFlows(index, impacted);

        boolean touchesSecurity = impactedTypes.stream().anyMatch(t -> t.layer().equals("security"));
        String risk;
        String rationale;
        if (touchesSecurity || (!tables.isEmpty() && !apis.isEmpty() && modules.size() > 1)) {
            risk = "HIGH";
            rationale = "change spans " + modules + (touchesSecurity ? " including security configuration" : "") + " with schema and API impact";
        } else if (!tables.isEmpty() || !apis.isEmpty()) {
            risk = "MEDIUM";
            rationale = "touches " + (tables.isEmpty() ? "" : "tables " + tables + " ") + (apis.isEmpty() ? "" : apis.size() + " public endpoint(s)");
        } else {
            risk = "LOW";
            rationale = "internal change, no schema or public API impact";
        }

        Map<String, Integer> stats = new LinkedHashMap<>();
        stats.put("typesScanned", index.types().size());
        stats.put("dependencyEdges", index.edgeCount());
        stats.put("testFilesScanned", index.testFileCount());
        stats.put("impactedTypes", impactedTypes.size());

        return new Impact(seeds, primaryModules,
                impactedTypes.stream().map(JavaType::simpleName).toList(),
                impactedTypes.stream().map(JavaType::path).toList(),
                new ArrayList<>(modules), new ArrayList<>(apis), flows, tables, new ArrayList<>(tests),
                "V" + index.nextMigrationVersion(), risk, rationale, stats);
    }

    /**
     * For every impacted entry point, follows each of its <b>direct</b> collaborators separately
     * (breadth-first, same module only) to the repositories they reach, then to the table of the
     * entity each repository manages. Tracing per first hop keeps distinct routes visible: a
     * controller that writes through one service and reads through another yields both flows.
     */
    static List<DataFlow> dataFlows(CodebaseIndex index, Set<String> impacted) {
        Set<DataFlow> flows = new LinkedHashSet<>();
        for (String entryFqn : impacted) {
            JavaType entry = index.type(entryFqn);
            boolean controller = entry.annotations().contains("RestController");
            boolean worker = entry.source().contains("SmartLifecycle") || entry.annotations().contains("Scheduled");
            if (!controller && !worker) {
                continue;
            }
            List<String> endpoints = controller ? entry.endpoints() : List.of("background");
            for (String firstHop : index.dependenciesOf(entryFqn)) {
                if (!index.type(firstHop).module().equals(entry.module())) {
                    continue;
                }
                Map<String, String> parent = new LinkedHashMap<>();
                parent.put(entryFqn, null);
                parent.put(firstHop, entryFqn);
                ArrayDeque<String> queue = new ArrayDeque<>(List.of(firstHop));
                while (!queue.isEmpty()) {
                    String current = queue.poll();
                    if (isRepository(index.type(current))) {
                        tableOf(index, current).ifPresent(table ->
                                flows.add(new DataFlow(entry.simpleName(), endpoints, pathTo(index, parent, current), table)));
                        continue;
                    }
                    for (String dep : index.dependenciesOf(current)) {
                        if (!parent.containsKey(dep) && index.type(dep).module().equals(entry.module())) {
                            parent.put(dep, current);
                            queue.add(dep);
                        }
                    }
                }
            }
        }
        List<DataFlow> sorted = new ArrayList<>(flows);
        sorted.sort(Comparator.comparing(DataFlow::entry).thenComparing(DataFlow::table)
                .thenComparing(f -> String.join(">", f.path())));
        return sorted;
    }

    private static boolean isRepository(JavaType type) {
        return type.layer().equals("repository") && type.simpleName().endsWith("Repository");
    }

    private static Optional<String> tableOf(CodebaseIndex index, String repositoryFqn) {
        return index.dependenciesOf(repositoryFqn).stream().map(index::type)
                .filter(t -> t.table() != null).map(JavaType::table).findFirst();
    }

    private static List<String> pathTo(CodebaseIndex index, Map<String, String> parent, String target) {
        LinkedList<String> path = new LinkedList<>();
        for (String at = target; at != null; at = parent.get(at)) {
            path.addFirst(index.type(at).simpleName());
        }
        return path;
    }

    private static int countOccurrences(String haystack, String needle) {
        int count = 0;
        for (int i = haystack.indexOf(needle); i >= 0; i = haystack.indexOf(needle, i + needle.length())) {
            count++;
        }
        return count;
    }
}
