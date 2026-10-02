package com.agentic.orchestration.model;

public enum ApprovalStatus {
    PENDING,
    APPROVED,
    REJECTED,
    /** Nobody decided before the deadline; treated as a rejection. */
    EXPIRED,
    /** Closed by the system (run stopped/failed, or the artifact changed): no decision possible. */
    WITHDRAWN
}
