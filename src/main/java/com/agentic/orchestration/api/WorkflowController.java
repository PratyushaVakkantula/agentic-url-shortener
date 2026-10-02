package com.agentic.orchestration.api;

import com.agentic.orchestration.engine.WorkflowCatalog;
import com.agentic.orchestration.engine.WorkflowEngine;
import com.agentic.orchestration.engine.UnknownWorkflowException;
import com.agentic.orchestration.event.RunSummary;
import com.agentic.orchestration.metrics.ReliabilityMetrics;
import com.agentic.orchestration.metrics.ReliabilityReport;
import com.agentic.orchestration.model.Approval;
import com.agentic.orchestration.model.Artifact;
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

    @PostMapping("/runs/{runId}/approvals/{approvalId}")
    @PreAuthorize("hasRole('APPROVER')")
    @Operation(summary = "Approve or reject a checkpoint (APPROVER, never the run's initiator)",
            description = "artifactHash must equal the pending artifact's hash. Errors: 403 self-approval, "
                    + "409 stale hash or already decided, 404 unknown approval.")
    public Approval decide(@PathVariable String runId, @PathVariable String approvalId,
                           @Valid @RequestBody ApprovalDecisionRequest request, Principal principal) {
        return engine.decide(runId, approvalId, request.decision() == ApprovalDecisionRequest.Decision.APPROVE,
                principal.getName(), request.artifactHash(), request.comment());
    }

    @PostMapping("/runs/{runId}/stop")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Safe-stop a run (ADMIN)",
            description = "No new stages start, in-flight stages finish, open approvals are withdrawn; ends STOPPED. Idempotent.")
    public ResponseEntity<Void> stop(@PathVariable String runId, @Valid @RequestBody StopRunRequest request, Principal principal) {
        engine.requestStop(runId, principal.getName(), request.reason());
        return ResponseEntity.accepted().build();
    }

    @PostMapping("/runs/{runId}/stages/{stageId}/revisions")
    @PreAuthorize("hasRole('REQUESTER')")
    @Operation(summary = "Revise a stage's output (REQUESTER)",
            description = "Replaces a SUCCEEDED stage's artifact. Same policies as agent output; downstream stages "
                    + "that consumed the old version are re-planned. 409 if not revisable, 422 if a policy blocks it.")
    public Artifact revise(@PathVariable String runId, @PathVariable String stageId,
                           @Valid @RequestBody RevisionRequest request, Principal principal) {
        return engine.revise(runId, stageId, request.content(), principal.getName(), request.reason());
    }

    @GetMapping("/runs/{runId}/metrics")
    @Operation(summary = "Reliability metrics of one run")
    public ReliabilityReport runMetrics(@PathVariable String runId) {
        return ReliabilityMetrics.compute(List.of(engine.events(runId)));
    }

    @GetMapping("/metrics")
    @Operation(summary = "Reliability metrics across all runs",
            description = "Success rate, retry/rollback frequency, MTTR, end-to-end latency, governance and re-planning.")
    public ReliabilityReport metrics() {
        return ReliabilityMetrics.compute(engine.allRunEvents());
    }

    @GetMapping("/runs/{runId}/events")
    @Operation(summary = "Audit trail: every event of the run, in order")
    public List<AuditEntry> events(@PathVariable String runId) {
        return engine.events(runId).stream().map(AuditEntry::of).toList();
    }
}
