package com.agentic;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * The documentation makes claims ("requirement X is proven by test Y"). This test keeps them true:
 * renaming or deleting a test, or moving a document, fails the build instead of silently leaving
 * the docs wrong.
 */
class DocumentationConsistencyTest {

    private static final Path ROOT = Path.of(".");
    /** Test method names as written in docs: `Class.method` or `…method` (camelCase, ≥ 12 chars). */
    private static final Pattern TEST_REF = Pattern.compile("[.…]([a-z][A-Za-z0-9_]{11,})\\b");
    private static final Pattern LINK = Pattern.compile("\\]\\((?!https?://|#|mailto:)([^)#\\s]+)");

    /**
     * In every table row, method names in the last column ("Verified by") must exist in the tests,
     * and method names in earlier columns ("Implementation") must exist in the production code.
     */
    @Test
    void everyMethodNamedInTheTraceabilityMatrixExists() throws IOException {
        String allTests = readAll(ROOT.resolve("src/test/java"));
        String allMain = readAll(ROOT.resolve("src/main/java"));
        Set<String> testRefs = new LinkedHashSet<>();
        Set<String> mainRefs = new LinkedHashSet<>();
        for (String line : Files.readAllLines(ROOT.resolve("docs/traceability.md"))) {
            if (!line.startsWith("|") || line.startsWith("|---")) {
                continue;
            }
            String[] cells = line.split("\\|");
            for (int i = 1; i < cells.length; i++) {
                Set<String> target = i == cells.length - 1 ? testRefs : mainRefs;
                for (String code : backticked(cells[i])) {
                    Matcher m = TEST_REF.matcher(code);
                    while (m.find()) {
                        target.add(m.group(1));
                    }
                }
            }
        }

        assertThat(testRefs).as("matrix should reference many tests").hasSizeGreaterThan(50);
        assertThat(missingIn(testRefs, allTests)).as("tests named in docs/traceability.md but not found in src/test").isEmpty();
        assertThat(missingIn(mainRefs, allMain)).as("code named in docs/traceability.md but not found in src/main").isEmpty();
    }

    private static List<String> missingIn(Set<String> names, String source) {
        return names.stream().filter(n -> !Pattern.compile("\\b" + n + "\\b").matcher(source).find()).toList();
    }

    @Test
    void everyRelativeLinkInTheDocsPointsToAnExistingFile() throws IOException {
        List<String> broken = new ArrayList<>();
        try (Stream<Path> docs = Stream.concat(Stream.of(ROOT.resolve("README.md")),
                Files.walk(ROOT.resolve("docs")).filter(p -> p.toString().endsWith(".md")))) {
            for (Path doc : docs.toList()) {
                Matcher m = LINK.matcher(Files.readString(doc));
                while (m.find()) {
                    String target = m.group(1).split(":")[0]; // drop ":line" suffixes
                    if (!Files.exists(doc.getParent().resolve(target).normalize())) {
                        broken.add(ROOT.relativize(doc) + " → " + m.group(1));
                    }
                }
            }
        }
        assertThat(broken).as("broken relative links").isEmpty();
    }

    private static List<String> backticked(String markdown) {
        List<String> out = new ArrayList<>();
        Matcher m = Pattern.compile("`([^`]+)`").matcher(markdown);
        while (m.find()) {
            out.add(m.group(1));
        }
        return out;
    }

    private static String readAll(Path dir) throws IOException {
        StringBuilder sb = new StringBuilder();
        try (Stream<Path> files = Files.walk(dir)) {
            for (Path f : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                sb.append(Files.readString(f)).append('\n');
            }
        }
        return sb.toString();
    }
}
