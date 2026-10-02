package com.agentic.orchestration.event;

import java.util.Arrays;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import tools.jackson.databind.json.JsonMapper;

/**
 * Serialises events as (type, schemaVersion, JSON payload). The type registry is derived from the
 * sealed hierarchy, so a new event type is registered automatically and type names cannot drift.
 *
 * <p>All event types are at schema version 1. When a payload shape changes, bump its version
 * here and add an upcaster in {@link #decode} that converts old payloads; never rewrite history.
 */
public final class RunEventCodec {

    public static final int SCHEMA_VERSION = 1;

    private static final Map<String, Class<? extends RunEvent>> TYPES = Arrays.stream(RunEvent.class.getPermittedSubclasses())
            .map(c -> c.asSubclass(RunEvent.class))
            .collect(Collectors.toUnmodifiableMap(Class::getSimpleName, Function.identity()));

    private final JsonMapper mapper;

    public RunEventCodec(JsonMapper mapper) {
        this.mapper = mapper;
    }

    public static String typeOf(RunEvent event) {
        return event.getClass().getSimpleName();
    }

    public String encode(RunEvent event) {
        return mapper.writeValueAsString(event);
    }

    public RunEvent decode(String type, int schemaVersion, String payload) {
        Class<? extends RunEvent> clazz = TYPES.get(type);
        if (clazz == null) {
            throw new IllegalStateException("Unknown event type '" + type + "'");
        }
        if (schemaVersion != SCHEMA_VERSION) {
            throw new IllegalStateException("No upcaster for " + type + " schema v" + schemaVersion);
        }
        return mapper.readValue(payload, clazz);
    }
}
