package com.agentic.orchestration.model;

import java.time.Instant;
import java.util.List;

/**
 * A human checkpoint (OR-5, OR-14). Bound to one exact artifact version by {@code artifact}'s
 * content hash: a decision is only accepted if the approver submits the same hash, proving they
 * reviewed what is actually being accepted.
 */
public record Approval(String approvalId, String stageId, int attempt, ArtifactRef artifact, List<String> reasons,
                       Instant requestedAt, Instant expiresAt, ApprovalStatus status, String decidedBy,
                       String comment, Instant decidedAt) {

    public Approval {
        reasons = List.copyOf(reasons);
    }

    public Approval decide(ApprovalStatus decision, String actor, String note, Instant at) {
        return new Approval(approvalId, stageId, attempt, artifact, reasons, requestedAt, expiresAt, decision, actor, note, at);
    }
}
