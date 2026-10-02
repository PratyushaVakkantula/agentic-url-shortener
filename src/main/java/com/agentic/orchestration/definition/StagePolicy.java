package com.agentic.orchestration.definition;

import com.agentic.orchestration.agent.Agent;
import java.time.Duration;

/**
 * Governance settings of one stage.
 *
 * @param timeout          per-attempt limit; {@code null} = engine default (OR-16)
 * @param fallback         agent tried once after the primary's retries are exhausted; may be null
 * @param compensation     undo action used during rollback; may be null (nothing to undo)
 * @param approvalReason   non-null marks the stage high-impact: its output always needs human
 *                         approval before it is accepted (OR-5)
 */
public record StagePolicy(RetryPolicy retry, Duration timeout, Agent fallback, Compensation compensation,
                          String approvalReason) {

    public static final StagePolicy DEFAULT = new StagePolicy(RetryPolicy.NONE, null, null, null, null);

    public StagePolicy {
        retry = retry == null ? RetryPolicy.NONE : retry;
        if (timeout != null && (timeout.isZero() || timeout.isNegative())) {
            throw new WorkflowDefinitionException("timeout must be positive");
        }
    }

    public boolean requiresApproval() {
        return approvalReason != null;
    }

    StagePolicy withRetry(RetryPolicy r) {
        return new StagePolicy(r, timeout, fallback, compensation, approvalReason);
    }

    StagePolicy withTimeout(Duration t) {
        return new StagePolicy(retry, t, fallback, compensation, approvalReason);
    }

    StagePolicy withFallback(Agent f) {
        return new StagePolicy(retry, timeout, f, compensation, approvalReason);
    }

    StagePolicy withCompensation(Compensation c) {
        return new StagePolicy(retry, timeout, fallback, c, approvalReason);
    }

    StagePolicy withApproval(String reason) {
        return new StagePolicy(retry, timeout, fallback, compensation, reason);
    }
}
