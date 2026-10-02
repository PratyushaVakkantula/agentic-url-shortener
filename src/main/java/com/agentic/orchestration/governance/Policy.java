package com.agentic.orchestration.governance;

/**
 * A guardrail evaluated against every stage output before it is accepted. Policies are pure
 * functions of their input: no I/O, no state, so evaluation is fast, repeatable and safe to
 * re-run during replay or retry.
 */
public interface Policy {

    String name();

    PolicyCategory category();

    PolicyDecision evaluate(PolicyInput input);
}
