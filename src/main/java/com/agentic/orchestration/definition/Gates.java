package com.agentic.orchestration.definition;

import java.util.function.Predicate;
import tools.jackson.databind.JsonNode;

/** Reusable gate building blocks. */
public final class Gates {

    private Gates() {
    }

    /** Entry gate: an upstream artifact exists and has a non-empty field. */
    public static Gate upstreamHas(String stageId, String field) {
        return Gate.of("upstream:" + stageId + "." + field,
                in -> in.context().hasArtifact(stageId) && nonEmpty(in.context().artifact(stageId).content().get(field)),
                "upstream stage '" + stageId + "' did not provide '" + field + "'");
    }

    /** Exit gate: the output has a non-empty field. */
    public static Gate outputHas(String field) {
        return Gate.of("output:" + field,
                in -> in.output() != null && nonEmpty(in.output().get(field)),
                "output is missing required field '" + field + "'");
    }

    /** Exit gate: a field of the output satisfies a predicate. */
    public static Gate outputField(String field, Predicate<JsonNode> check, String failureReason) {
        return Gate.of("output:" + field + ":check",
                in -> in.output() != null && in.output().get(field) != null && check.test(in.output().get(field)),
                failureReason);
    }

    private static boolean nonEmpty(JsonNode node) {
        return node != null && !node.isNull() && !(node.isContainer() && node.isEmpty())
                && !(node.isString() && node.asString().isBlank());
    }
}
