package com.agentic.orchestration.definition;

public record GateResult(boolean passed, String reason) {

    private static final GateResult PASS = new GateResult(true, "ok");

    public static GateResult pass() {
        return PASS;
    }

    public static GateResult fail(String reason) {
        return new GateResult(false, reason);
    }
}
