package com.agentic.orchestration.engine;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * SHA-256 over canonical JSON (object keys sorted recursively). Two outputs with the same
 * content hash identically regardless of key order, which is what makes "did this artifact
 * change?" a reliable, cheap comparison for re-planning.
 */
public final class ContentHasher {

    private final JsonMapper mapper;

    public ContentHasher(JsonMapper mapper) {
        this.mapper = mapper;
    }

    public String hash(JsonNode content) {
        Object canonical = canonicalize(mapper.convertValue(content, Object.class));
        byte[] bytes = mapper.writeValueAsString(canonical).getBytes(StandardCharsets.UTF_8);
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required by every JVM", e);
        }
    }

    private static Object canonicalize(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> sorted = new TreeMap<>();
            map.forEach((k, v) -> sorted.put(String.valueOf(k), canonicalize(v)));
            return sorted;
        }
        if (value instanceof List<?> list) {
            return list.stream().map(ContentHasher::canonicalize).toList();
        }
        return value;
    }
}
