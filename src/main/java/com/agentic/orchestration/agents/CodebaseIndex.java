package com.agentic.orchestration.agents;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * A static model of a Java codebase, built from source files (OR-17): types, their module and
 * layer, internal dependencies (imports plus same-package references), REST endpoints, JPA
 * tables, Flyway migrations, and which tests mention which types.
 *
 * <p>Deliberately lightweight (regex over source, no compiler): fast, dependency-free and good
 * enough for impact analysis, where over-approximating is safe and missing a dependency is not.
 * Same-package references are found by token matching, which over-approximates; that is the
 * safe direction.
 */
public final class CodebaseIndex {

    private static final Pattern PACKAGE = Pattern.compile("^package\\s+([\\w.]+);", Pattern.MULTILINE);
    private static final Pattern IMPORT = Pattern.compile("^import\\s+(?:static\\s+)?([\\w.]+?)(?:\\.\\*)?;", Pattern.MULTILINE);
    private static final Pattern TYPE = Pattern.compile("^(?:public\\s+)?(?:final\\s+|abstract\\s+|sealed\\s+)*(?:class|interface|record|enum)\\s+(\\w+)", Pattern.MULTILINE);
    private static final Pattern ANNOTATION = Pattern.compile("@(\\w+)");
    private static final Pattern CLASS_MAPPING = Pattern.compile("@RequestMapping\\(\\s*\"([^\"]*)\"");
    private static final Pattern METHOD_MAPPING = Pattern.compile("@(Get|Post|Put|Delete|Patch)Mapping(?:\\(\\s*(?:value\\s*=\\s*)?\"([^\"]*)\")?");
    private static final Pattern TABLE = Pattern.compile("@Table\\(\\s*name\\s*=\\s*\"(\\w+)\"");
    private static final Pattern IDENTIFIER = Pattern.compile("\\b[A-Z]\\w+\\b");
    private static final Pattern MIGRATION = Pattern.compile("V(\\d+)__.+\\.sql");

    /** One production type. {@code module} is the segment after the base package; {@code layer} the one after it. */
    public record JavaType(String fqn, String simpleName, String module, String layer, String path, Set<String> annotations,
                           List<String> endpoints, String table, int lines, String source) {
    }

    private final String basePackage;
    private final Map<String, JavaType> types = new LinkedHashMap<>();
    private final Map<String, Set<String>> dependencies = new HashMap<>();
    private final Map<String, Set<String>> dependents = new HashMap<>();
    private final Map<String, Set<String>> testsByType = new HashMap<>();
    private final List<String> migrations = new ArrayList<>();
    private int testFiles;

    private CodebaseIndex(String basePackage) {
        this.basePackage = basePackage;
    }

    /**
     * @param root        repository root (containing {@code src/main/java})
     * @param basePackage e.g. {@code com.agentic}; modules are its direct sub-packages
     */
    public static CodebaseIndex scan(Path root, String basePackage) {
        Path main = root.resolve("src/main/java");
        if (!Files.isDirectory(main)) {
            throw new IllegalStateException("No source tree at " + main.toAbsolutePath()
                    + " (static analysis needs the repository checked out)");
        }
        CodebaseIndex index = new CodebaseIndex(basePackage);
        javaFiles(main).forEach(file -> index.addType(root, file));
        index.resolveDependencies();
        Path tests = root.resolve("src/test/java");
        if (Files.isDirectory(tests)) {
            javaFiles(tests).forEach(index::addTest);
        }
        Path migrationDir = root.resolve("src/main/resources/db/migration");
        if (Files.isDirectory(migrationDir)) {
            try (Stream<Path> files = Files.list(migrationDir)) {
                files.map(p -> p.getFileName().toString()).filter(n -> MIGRATION.matcher(n).matches()).sorted()
                        .forEach(index.migrations::add);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
        return index;
    }

    private static List<Path> javaFiles(Path dir) {
        try (Stream<Path> files = Files.walk(dir)) {
            return files.filter(p -> p.toString().endsWith(".java")).sorted().toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private void addType(Path root, Path file) {
        String source = read(file);
        Matcher pkg = PACKAGE.matcher(source);
        Matcher type = TYPE.matcher(source);
        if (!pkg.find() || !type.find()) {
            return;
        }
        String packageName = pkg.group(1);
        String simple = type.group(1);
        String relative = packageName.startsWith(basePackage + ".") ? packageName.substring(basePackage.length() + 1) : packageName;
        String[] parts = relative.split("\\.");
        String module = parts[0];
        String layer = parts.length > 1 ? parts[1] : "(root)";

        Set<String> annotations = new LinkedHashSet<>();
        ANNOTATION.matcher(source).results().forEach(r -> annotations.add(r.group(1)));

        List<String> endpoints = new ArrayList<>();
        if (annotations.contains("RestController")) {
            Matcher base = CLASS_MAPPING.matcher(source);
            String prefix = base.find() ? base.group(1) : "";
            METHOD_MAPPING.matcher(source).results().forEach(r ->
                    endpoints.add(r.group(1).toUpperCase() + " " + (prefix + (r.group(2) == null ? "" : r.group(2)))));
        }
        Matcher table = TABLE.matcher(source);
        String fqn = packageName + "." + simple;
        types.put(fqn, new JavaType(fqn, simple, module, layer, root.relativize(file).toString(), annotations,
                endpoints, table.find() ? table.group(1) : null, (int) source.lines().count(), source));
    }

    private void resolveDependencies() {
        Map<String, List<JavaType>> byPackage = new HashMap<>();
        types.values().forEach(t -> byPackage.computeIfAbsent(packageOf(t.fqn()), k -> new ArrayList<>()).add(t));
        for (JavaType t : types.values()) {
            Set<String> deps = new LinkedHashSet<>();
            IMPORT.matcher(t.source()).results().map(r -> r.group(1)).filter(types::containsKey).forEach(deps::add);
            Set<String> identifiers = new LinkedHashSet<>();
            IDENTIFIER.matcher(t.source()).results().forEach(r -> identifiers.add(r.group()));
            for (JavaType sibling : byPackage.get(packageOf(t.fqn()))) {
                if (!sibling.fqn().equals(t.fqn()) && identifiers.contains(sibling.simpleName())) {
                    deps.add(sibling.fqn());
                }
            }
            dependencies.put(t.fqn(), deps);
            deps.forEach(d -> dependents.computeIfAbsent(d, k -> new LinkedHashSet<>()).add(t.fqn()));
        }
    }

    private void addTest(Path file) {
        testFiles++;
        String source = read(file);
        String name = file.getFileName().toString().replace(".java", "");
        Set<String> identifiers = new LinkedHashSet<>();
        IDENTIFIER.matcher(source).results().forEach(r -> identifiers.add(r.group()));
        types.values().stream().filter(t -> identifiers.contains(t.simpleName()))
                .forEach(t -> testsByType.computeIfAbsent(t.fqn(), k -> new LinkedHashSet<>()).add(name));
    }

    public Collection<JavaType> types() {
        return types.values();
    }

    public JavaType type(String fqn) {
        return types.get(fqn);
    }

    public Set<String> dependenciesOf(String fqn) {
        return dependencies.getOrDefault(fqn, Set.of());
    }

    public Set<String> dependentsOf(String fqn) {
        return dependents.getOrDefault(fqn, Set.of());
    }

    /** Types that (transitively, up to {@code depth} hops) depend on any of {@code seeds}, plus the seeds. */
    public Set<String> blastRadius(Collection<String> seeds, int depth) {
        Set<String> seen = new LinkedHashSet<>(seeds);
        Deque<Map.Entry<String, Integer>> todo = new ArrayDeque<>();
        seeds.forEach(s -> todo.add(Map.entry(s, 0)));
        while (!todo.isEmpty()) {
            var next = todo.poll();
            if (next.getValue() >= depth) {
                continue;
            }
            for (String dependent : dependentsOf(next.getKey())) {
                if (seen.add(dependent)) {
                    todo.add(Map.entry(dependent, next.getValue() + 1));
                }
            }
        }
        return seen;
    }

    public Set<String> testsCovering(String fqn) {
        return testsByType.getOrDefault(fqn, Set.of());
    }

    public List<String> migrations() {
        return List.copyOf(migrations);
    }

    /** Next Flyway version number (e.g. 5 when V1..V4 exist). */
    public int nextMigrationVersion() {
        return migrations.stream().map(MIGRATION::matcher).filter(Matcher::matches)
                .mapToInt(m -> Integer.parseInt(m.group(1))).max().orElse(0) + 1;
    }

    public int edgeCount() {
        return dependencies.values().stream().mapToInt(Set::size).sum();
    }

    public int testFileCount() {
        return testFiles;
    }

    private static String packageOf(String fqn) {
        return fqn.substring(0, fqn.lastIndexOf('.'));
    }

    private static String read(Path file) {
        try {
            return Files.readString(file);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
