package com.agentic.orchestration.agents;

import java.util.ArrayList;
import java.util.List;
import tools.jackson.databind.JsonNode;

/**
 * Null-safe readers for upstream artifacts. Agents must tolerate humans revising an artifact into
 * a slightly different shape, so missing fields read as empty rather than throwing.
 */
final class Json {

    private Json() {
    }

    static List<String> strings(JsonNode node, String field) {
        List<String> out = new ArrayList<>();
        JsonNode array = node == null ? null : node.get(field);
        if (array != null && array.isArray()) {
            array.forEach(n -> out.add(n.isString() ? n.asString() : n.toString()));
        }
        return out;
    }

    /** Values of {@code field} inside each object of array {@code arrayField}. */
    static List<String> pluck(JsonNode node, String arrayField, String field) {
        List<String> out = new ArrayList<>();
        JsonNode array = node == null ? null : node.get(arrayField);
        if (array != null && array.isArray()) {
            array.forEach(n -> {
                String value = n.path(field).asString("");
                if (!value.isBlank()) {
                    out.add(value);
                }
            });
        }
        return out;
    }

    static List<JsonNode> objects(JsonNode node, String arrayField) {
        List<JsonNode> out = new ArrayList<>();
        JsonNode array = node == null ? null : node.get(arrayField);
        if (array != null && array.isArray()) {
            array.forEach(out::add);
        }
        return out;
    }

    static String text(JsonNode node, String field, String fallback) {
        return node == null ? fallback : node.path(field).asString(fallback);
    }
}
