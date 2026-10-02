package com.agentic.orchestration.definition;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.agentic.orchestration.agent.Agent;
import com.agentic.orchestration.agent.AgentResult;
import org.junit.jupiter.api.Test;

class WorkflowDefinitionTest {

    private static final Agent NOOP = new Agent() {
        @Override
        public String name() {
            return "noop";
        }

        @Override
        public AgentResult execute(com.agentic.orchestration.agent.StageContext context) {
            return AgentResult.of("ok");
        }
    };

    /** a → (b, c) → d */
    private static WorkflowDefinition diamond() {
        return WorkflowDefinition.builder("diamond", 1)
                .stage("a", NOOP).add()
                .stage("b", NOOP).dependsOn("a").add()
                .stage("c", NOOP).dependsOn("a").add()
                .stage("d", NOOP).dependsOn("b", "c").add()
                .build();
    }

    @Test
    void computesDeterministicTopologicalOrder() {
        assertThat(diamond().topologicalOrder()).containsExactly("a", "b", "c", "d");
    }

    @Test
    void topologicalOrderRespectsDependenciesRegardlessOfDeclarationOrder() {
        WorkflowDefinition wf = WorkflowDefinition.builder("reversed", 1)
                .stage("docs", NOOP).dependsOn("build").add()
                .stage("build", NOOP).dependsOn("design").add()
                .stage("design", NOOP).add()
                .build();
        assertThat(wf.topologicalOrder()).containsExactly("design", "build", "docs");
    }

    @Test
    void computesTransitiveAncestorsAndDescendants() {
        WorkflowDefinition wf = diamond();
        assertThat(wf.ancestorsOf("d")).containsExactlyInAnyOrder("a", "b", "c");
        assertThat(wf.ancestorsOf("b")).containsExactly("a");
        assertThat(wf.ancestorsOf("a")).isEmpty();
        assertThat(wf.descendantsOf("a")).containsExactlyInAnyOrder("b", "c", "d");
        assertThat(wf.descendantsOf("c")).containsExactly("d");
        assertThat(wf.dependentsOf("a")).containsExactly("b", "c");
    }

    @Test
    void rejectsCycleAndNamesThePath() {
        assertThatThrownBy(() -> WorkflowDefinition.builder("cyclic", 1)
                .stage("a", NOOP).add()
                .stage("b", NOOP).dependsOn("a", "d").add()
                .stage("c", NOOP).dependsOn("b").add()
                .stage("d", NOOP).dependsOn("c").add()
                .build())
                .isInstanceOf(WorkflowDefinitionException.class)
                .hasMessageContaining("cycle")
                .hasMessageContaining("b -> c -> d -> b");
    }

    @Test
    void rejectsUnknownAndSelfDependencies() {
        assertThatThrownBy(() -> WorkflowDefinition.builder("w", 1).stage("a", NOOP).dependsOn("ghost").add().build())
                .hasMessageContaining("unknown stage 'ghost'");
        assertThatThrownBy(() -> WorkflowDefinition.builder("w", 1).stage("a", NOOP).dependsOn("a").add().build())
                .hasMessageContaining("depends on itself");
    }

    @Test
    void rejectsDuplicateStageInvalidIdAndEmptyWorkflow() {
        assertThatThrownBy(() -> WorkflowDefinition.builder("w", 1).stage("a", NOOP).add().stage("a", NOOP).add())
                .hasMessageContaining("Duplicate stage id 'a'");
        assertThatThrownBy(() -> WorkflowDefinition.builder("w", 1).stage("Bad Id", NOOP).add())
                .isInstanceOf(WorkflowDefinitionException.class);
        assertThatThrownBy(() -> WorkflowDefinition.builder("w", 1).build())
                .hasMessageContaining("has no stages");
    }
}
