package com.agentic.orchestration.engine;

/** A human action was refused by governance rules; {@link #violation()} says which. */
public class GovernanceException extends RuntimeException {

    public enum Violation {
        /** The run's initiator tried to approve its own checkpoint (separation of duties). */
        SELF_APPROVAL_FORBIDDEN,
        /** The approver's artifact hash does not match the pending artifact: they reviewed something else. */
        STALE_APPROVAL,
        APPROVAL_NOT_FOUND,
        APPROVAL_NOT_PENDING,
        /** The run has finished; it no longer accepts commands. */
        RUN_NOT_ACTIVE
    }

    private final Violation violation;

    public GovernanceException(Violation violation, String message) {
        super(message);
        this.violation = violation;
    }

    public Violation violation() {
        return violation;
    }
}
