package com.agentic.orchestration.model;

import java.util.Map;
import java.util.Objects;

/**
 * The input to a workflow run: a requirement as a human wrote it, possibly vague.
 *
 * @param attributes optional structured hints (e.g. {@code targetModule}); free-form by design,
 *                   since interpreting them is the requirements agent's job
 */
public record Requirement(String title, String description, Map<String, String> attributes) {

    public Requirement {
        Objects.requireNonNull(title, "title");
        description = description == null ? "" : description;
        attributes = attributes == null ? Map.of() : Map.copyOf(attributes);
    }

    public Requirement(String title, String description) {
        this(title, description, Map.of());
    }
}
