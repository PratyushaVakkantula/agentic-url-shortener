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

    /** Polls the engine until a stage reaches a status (engine state is updated asynchronously). */
    static com.agentic.orchestration.state.RunView awaitStage(WorkflowEngine engine, String runId, String stageId,
                                                               com.agentic.orchestration.model.StageStatus status)
            throws InterruptedException {
        long deadline = System.nanoTime() + java.time.Duration.ofSeconds(10).toNanos();
        while (true) {
            var view = engine.find(runId).orElseThrow();
            if (view.stage(stageId).status() == status) {
                return view;
            }
            if (System.nanoTime() > deadline) {
                throw new AssertionError("stage " + stageId + " is " + view.stage(stageId).status() + ", never reached " + status);
            }
            Thread.sleep(5);
        }
    }

    static Agent failing(String name, String message) {
        return agent(name, ctx -> {
            throw new IllegalStateException(message);
        });
    }
}
