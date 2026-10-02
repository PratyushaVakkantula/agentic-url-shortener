package com.agentic.orchestration.agent;

/**
 * A unit of autonomous work in a workflow stage.
 *
 * <p>The autonomy boundary (OR-13) is built into the contract: an agent receives a read-only
 * {@link StageContext} and returns a <i>proposal</i> ({@link AgentResult}). It cannot change
 * workflow state, approve anything, or apply its output; only the engine does that, under
 * governance. Agents here are deterministic Java (A-8); an LLM-backed agent implements the same
 * interface.
 *
 * <p>Implementations should be stateless and safe to call concurrently, and should check
 * {@link StageContext#isCancelled()} during long work so safe-stop is responsive.
 */
public interface Agent {

    /** Stable identifier recorded in decisions and audit events. */
    String name();

    AgentResult execute(StageContext context) throws Exception;
}
