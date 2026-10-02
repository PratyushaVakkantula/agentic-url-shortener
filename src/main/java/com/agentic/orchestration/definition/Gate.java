package com.agentic.orchestration.definition;

import java.util.Objects;
import java.util.function.Predicate;

/**
 * An entry or exit check on a stage (OR-2). Gates are pure predicates over the stage's context
 * and (for exit gates) its output. They hold no state and have no side effects, so they are
 * safe to re-evaluate on retry or replay.
 *
 * <p>Gates <b>fail closed</b>: if a gate throws, the engine treats it as failed.
 */
public interface Gate {

    String name();

    GateResult evaluate(GateInput input);

    static Gate of(String name, Predicate<GateInput> check, String failureReason) {
        Objects.requireNonNull(name);
        Objects.requireNonNull(check);
        return new Gate() {
            @Override
            public String name() {
                return name;
            }

            @Override
            public GateResult evaluate(GateInput input) {
                return check.test(input) ? GateResult.pass() : GateResult.fail(failureReason);
            }

            @Override
            public String toString() {
                return "Gate[" + name + "]";
            }
        };
    }
}
