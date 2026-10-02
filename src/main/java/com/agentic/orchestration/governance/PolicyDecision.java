package com.agentic.orchestration.governance;

/** @param reason human-readable; must never contain the sensitive value that triggered it */
public record PolicyDecision(String policy, PolicyCategory category, PolicyOutcome outcome, String reason) {

    public static PolicyDecision allow(Policy p) {
        return new PolicyDecision(p.name(), p.category(), PolicyOutcome.ALLOW, "no findings");
    }
}
