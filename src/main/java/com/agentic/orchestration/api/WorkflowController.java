package com.agentic.orchestration.api;

import com.agentic.orchestration.engine.WorkflowCatalog;
import com.agentic.orchestration.engine.WorkflowEngine;
import com.agentic.orchestration.engine.UnknownWorkflowException;
import com.agentic.orchestration.event.RunSummary;
import com.agentic.orchestration.model.Requirement;
import com.agentic.orchestration.state.RunView;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.net.URI;
import java.security.Principal;
import java.util.List;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
@Tag(name = "Workflows", description = "Agentic SDLC orchestration: definitions, runs, audit trail")
@SecurityRequirement(name = "basicAuth")
public class WorkflowController {

    private final WorkflowCatalog catalog;
    private final WorkflowEngine engine;

    public WorkflowController(WorkflowCatalog catalog, WorkflowEngine engine) {
        this.catalog = catalog;
        this.engine = engine;
    }

    @GetMapping("/workflows")
    @Operation(summary = "List workflow definitions (stage graph, gates)")
    public List<WorkflowDescription> workflows() {
        return catalog.all().stream().map(WorkflowDescription::of).toList();
    }

    @GetMapping("/workflows/{name}")
    @Operation(summary = "Describe one workflow definition")
    public WorkflowDescription workflow(@PathVariable String name) {
        return WorkflowDescription.of(catalog.find(name).orElseThrow(() -> new UnknownWorkflowException(name)));
    }

    @PostMapping("/workflows/{name}/runs")
    @PreAuthorize("hasRole('REQUESTER')")
    @Operation(summary = "Start a run (REQUESTER)", description = "Asynchronous: returns 202 with the run's location.")
    public ResponseEntity<Map<String, String>> start(@PathVariable String name, @Valid @RequestBody StartRunRequest request,
                                                     Principal principal) {
        Requirement requirement = new Requirement(request.title(), request.description(), request.attributes());
        String runId = engine.start(name, requirement, principal.getName());
        return ResponseEntity.accepted().location(URI.create("/api/v1/runs/" + runId)).body(Map.of("runId", runId));
    }

    @GetMapping("/runs")
    @Operation(summary = "List runs, newest first")
    public List<RunSummary> runs() {
        return engine.runs();
    }

    @GetMapping("/runs/{runId}")
    @Operation(summary = "Current state of a run: stages, gates, artifacts, decisions")
    public RunView run(@PathVariable String runId) {
        return engine.get(runId);
    }

    @GetMapping("/runs/{runId}/events")
    @Operation(summary = "Audit trail: every event of the run, in order")
    public List<AuditEntry> events(@PathVariable String runId) {
        return engine.events(runId).stream().map(AuditEntry::of).toList();
    }
}
