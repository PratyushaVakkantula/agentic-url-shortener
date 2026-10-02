package com.agentic.orchestration.engine;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class ContentHasherTest {

    private final JsonMapper mapper = JsonMapper.builder().build();
    private final ContentHasher hasher = new ContentHasher(mapper);

    @Test
    void hashIsIndependentOfKeyOrderAtEveryLevel() {
        Map<String, Object> one = new LinkedHashMap<>();
        one.put("b", 2);
        one.put("a", Map.of("y", 1, "x", List.of(Map.of("q", 1, "p", 2))));
        Map<String, Object> two = new LinkedHashMap<>();
        two.put("a", Map.of("x", List.of(Map.of("p", 2, "q", 1)), "y", 1));
        two.put("b", 2);

        assertThat(hasher.hash(mapper.valueToTree(one))).isEqualTo(hasher.hash(mapper.valueToTree(two)));
    }

    @Test
    void anyContentChangeChangesTheHash() {
        String base = hasher.hash(mapper.valueToTree(Map.of("tasks", List.of("a", "b"))));
        assertThat(hasher.hash(mapper.valueToTree(Map.of("tasks", List.of("b", "a"))))).isNotEqualTo(base); // order in lists matters
        assertThat(hasher.hash(mapper.valueToTree(Map.of("tasks", List.of("a", "b", "c"))))).isNotEqualTo(base);
        assertThat(base).hasSize(64).matches("[0-9a-f]+");
    }
}
