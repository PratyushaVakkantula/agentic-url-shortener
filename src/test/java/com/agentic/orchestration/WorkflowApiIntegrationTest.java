package com.agentic.orchestration;

import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.matchesPattern;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.agentic.orchestration.agent.Agent;
import com.agentic.orchestration.agent.AgentResult;
import com.agentic.orchestration.agent.StageContext;
import com.agentic.orchestration.definition.Gates;
import com.agentic.orchestration.definition.WorkflowDefinition;
import com.agentic.orchestration.engine.WorkflowEngine;
import com.agentic.orchestration.model.RunStatus;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(WorkflowApiIntegrationTest.TestWorkflow.class)
class WorkflowApiIntegrationTest {

    @TestConfiguration(proxyBeanMethods = false)
    static class TestWorkflow {
        @Bean
        WorkflowDefinition demoWorkflow() {
            Agent echo = new Agent() {
                @Override
                public String name() {
                    return "echo";
                }

                @Override
                public AgentResult execute(StageContext ctx) {
                    return AgentResult.of(Map.of("title", ctx.requirement().title()))
                            .withDecision("restated requirement", "first stage");
                }
            };
            return WorkflowDefinition.builder("demo", 3)
                    .stage("understand", echo).description("Restate the requirement").add()
                    .stage("plan", echo).dependsOn("understand").entryGate(Gates.upstreamHas("understand", "title")).add()
                    .build();
        }
    }

    @Autowired
    MockMvc mvc;

    @Autowired
    WorkflowEngine engine;

    @Autowired
    JsonMapper mapper;

    private static final String BODY = """
            {"title": "Add click limits", "description": "Stop after N clicks", "initiator": "mallory"}""";

    @Test
    void describesWorkflowGraphs() throws Exception {
        mvc.perform(get("/api/v1/workflows/demo").with(httpBasic("bob", "bob-pass")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(3))
                .andExpect(jsonPath("$.stages[1].id").value("plan"))
                .andExpect(jsonPath("$.stages[1].dependsOn[0]").value("understand"))
                .andExpect(jsonPath("$.stages[1].entryGates[0]").value("upstream:understand.title"));
    }

    @Test
    void requesterStartsRunAndInitiatorComesFromAuthenticationNotTheBody() throws Exception {
        String response = mvc.perform(post("/api/v1/workflows/demo/runs").with(httpBasic("alice", "alice-pass"))
                        .contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isAccepted())
                .andExpect(header().string("Location", matchesPattern("/api/v1/runs/[0-9a-f-]{36}")))
                .andReturn().getResponse().getContentAsString();
        String runId = mapper.readTree(response).get("runId").asString();

        assertThatRunSucceeds(runId);

        mvc.perform(get("/api/v1/runs/" + runId).with(httpBasic("bob", "bob-pass")))
                .andExpect(jsonPath("$.status").value("SUCCEEDED"))
                .andExpect(jsonPath("$.initiator").value("alice"))
                .andExpect(jsonPath("$.stages[1].gates[0].passed").value(true))
                // The plan stage's entry gate read 'understand', but its agent did not: lineage records
                // only what the agent read, so gate reads never fabricate a dependency.
                .andExpect(jsonPath("$.decisions[1].stageId").value("plan"))
                .andExpect(jsonPath("$.decisions[1].basedOn").isEmpty());

        mvc.perform(get("/api/v1/runs/" + runId + "/events").with(httpBasic("bob", "bob-pass")))
                .andExpect(jsonPath("$[0].seq").value(1))
                .andExpect(jsonPath("$[0].type").value("RunStarted"))
                .andExpect(jsonPath("$[0].event.initiator").value("alice"))
                .andExpect(jsonPath("$[-1:].type").value(hasItem("RunCompleted")));

        mvc.perform(get("/api/v1/runs").with(httpBasic("carol", "carol-pass")))
                .andExpect(jsonPath("$[*].runId").value(hasItem(runId)));
    }

    @Test
    void onlyRequestersMayStartRuns() throws Exception {
        mvc.perform(post("/api/v1/workflows/demo/runs").with(httpBasic("bob", "bob-pass"))
                        .contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/workflows/demo/runs")
                        .contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void validationAndNotFoundUseProblemDetails() throws Exception {
        mvc.perform(post("/api/v1/workflows/demo/runs").with(httpBasic("alice", "alice-pass"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"title\": \" \"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("VALIDATION_FAILED"));
        mvc.perform(post("/api/v1/workflows/nope/runs").with(httpBasic("alice", "alice-pass"))
                        .contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("WORKFLOW_NOT_FOUND"));
        mvc.perform(get("/api/v1/runs/00000000-0000-0000-0000-000000000000").with(httpBasic("alice", "alice-pass")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("RUN_NOT_FOUND"));
    }

    private void assertThatRunSucceeds(String runId) throws Exception {
        org.assertj.core.api.Assertions.assertThat(engine.awaitCompletion(runId, Duration.ofSeconds(10)).status())
                .isEqualTo(RunStatus.SUCCEEDED);
    }
}
