package com.agentic.orchestration.engine;

import com.agentic.orchestration.agent.Agent;
import com.agentic.orchestration.agent.AgentResult;
import com.agentic.orchestration.agent.StageContext;
import java.util.function.Function;

/** Small scripted agents for engine tests. */
final class TestAgents {

    private TestAgents() {
    }

    interface Body {
        AgentResult run(StageContext context) throws Exception;
    }

    static Agent agent(String name, Body body) {
        return new Agent() {
            @Override
            public String name() {
                return name;
            }

            @Override
            public AgentResult execute(StageContext context) throws Exception {
                return body.run(context);
            }
        };
    }

    /** Emits a fixed output. */
    static Agent emitting(String name, Object output) {
        return agent(name, ctx -> AgentResult.of(output));
    }

    /** Computes its output from the context (typically by reading upstream artifacts). */
    static Agent computing(String name, Function<StageContext, Object> fn) {
        return agent(name, ctx -> AgentResult.of(fn.apply(ctx)));
    }

    static Agent failing(String name, String message) {
        return agent(name, ctx -> {
            throw new IllegalStateException(message);
        });
    }
}
