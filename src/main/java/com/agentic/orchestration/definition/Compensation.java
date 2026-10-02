package com.agentic.orchestration.definition;

/**
 * Undo action for a stage that already succeeded, run during rollback (OR-7) in reverse
 * completion order. Must be idempotent: after a crash mid-rollback it may run again.
 *
 * @return a human-readable description of what was undone (recorded in the audit trail)
 */
@FunctionalInterface
public interface Compensation {

    String compensate(CompensationContext context) throws Exception;
}
