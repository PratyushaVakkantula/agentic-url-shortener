package com.agentic.orchestration.agents;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/** Small, deterministic text utilities shared by the agents. */
final class Text {

    private static final Pattern SENTENCE_SPLIT = Pattern.compile("(?<=[.!?;])\\s+|\\R+|\\s+-\\s+|^\\s*[-*]\\s+", Pattern.MULTILINE);
    private static final Pattern WORD = Pattern.compile("[A-Za-z][A-Za-z-]+");
    /** Short all-caps words (QR, URL, API, SLA) carry meaning despite their length. */
    private static final Pattern ACRONYM = Pattern.compile("[A-Z]{2,5}");
    private static final Pattern CAMEL = Pattern.compile("(?<=[a-z0-9])(?=[A-Z])|(?<=[A-Z])(?=[A-Z][a-z])|[_\\-/{}.:\\s]+");

    static final Set<String> STOPWORDS = Set.of(
            "the", "and", "for", "with", "that", "this", "from", "into", "should", "must", "shall", "will", "would",
            "could", "have", "has", "been", "being", "were", "when", "then", "than", "they", "them", "their", "there",
            "which", "while", "each", "every", "after", "before", "only", "also", "more", "most", "some", "such", "able",
            "user", "users", "need", "needs", "want", "make", "makes", "made", "using", "used", "allow", "allows",
            "per", "can", "not", "any", "all", "our", "your", "its", "via", "add", "adds", "new", "existing", "current",
            "support", "supports", "system", "service", "feature", "stop", "stops", "ensure", "number");

    private Text() {
    }

    static List<String> sentences(String text) {
        return Arrays.stream(SENTENCE_SPLIT.split(text == null ? "" : text))
                .map(String::strip)
                .map(s -> s.endsWith(".") ? s.substring(0, s.length() - 1) : s)
                .filter(s -> s.length() > 3)
                .toList();
    }

    /** Lower-case content words, singularised, de-duplicated, in order of first appearance. */
    static List<String> keywords(String text) {
        Set<String> out = new LinkedHashSet<>();
        var m = WORD.matcher(text == null ? "" : text);
        while (m.find()) {
            for (String part : m.group().split("-")) {
                if (ACRONYM.matcher(part).matches()) {
                    out.add(part.toLowerCase(Locale.ROOT));
                    continue;
                }
                String w = stem(part.toLowerCase(Locale.ROOT));
                if (w.length() >= 4 && !STOPWORDS.contains(w)) {
                    out.add(w);
                }
            }
        }
        return new ArrayList<>(out);
    }

    /** Splits identifiers like {@code RedirectService} or {@code short_link} into stemmed lower-case tokens. */
    static Set<String> identifierTokens(String identifier) {
        return Arrays.stream(CAMEL.split(identifier))
                .map(t -> stem(t.toLowerCase(Locale.ROOT)))
                .filter(t -> t.length() >= 3)
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    /** Crude but predictable stemming: enough to match "clicks"/"click", "redirecting"/"redirect". */
    static String stem(String word) {
        String w = word.toLowerCase(Locale.ROOT);
        if (w.endsWith("ing") && w.length() > 6) {
            w = w.substring(0, w.length() - 3);
        } else if (w.endsWith("ies") && w.length() > 4) {
            w = w.substring(0, w.length() - 3) + "y";
        } else if (w.endsWith("s") && !w.endsWith("ss") && w.length() > 4) {
            w = w.substring(0, w.length() - 1);
        }
        return w;
    }

    static String slug(String text, int maxWords) {
        return String.join("_", keywords(text).stream().limit(maxWords).toList());
    }

    static String capitalize(String s) {
        return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    static String pascal(String snake) {
        return Arrays.stream(snake.split("_")).map(Text::capitalize).collect(Collectors.joining());
    }
}
