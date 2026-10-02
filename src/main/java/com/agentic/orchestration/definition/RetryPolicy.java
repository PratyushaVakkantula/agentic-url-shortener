package com.agentic.orchestration.definition;

import java.time.Duration;

/**
 * Bounded retries with exponential backoff (OR-6). {@code maxAttempts} counts the primary agent's
 * attempts including the first, so {@code maxAttempts = 1} means "no retries".
 *
 * <p>No jitter: one coordinator per run means no thundering herd to spread out. Add jitter if
 * retries ever target a shared downstream service.
 */
public record RetryPolicy(int maxAttempts, Duration initialBackoff, Duration maxBackoff) {

    public static final RetryPolicy NONE = new RetryPolicy(1, Duration.ZERO, Duration.ZERO);

    public RetryPolicy {
        if (maxAttempts < 1 || maxAttempts > 10) {
            throw new WorkflowDefinitionException("maxAttempts must be between 1 and 10");
        }
        if (initialBackoff.isNegative() || maxBackoff.compareTo(initialBackoff) < 0) {
            throw new WorkflowDefinitionException("backoff must satisfy 0 <= initial <= max");
        }
    }

    public static RetryPolicy of(int maxAttempts, Duration initialBackoff) {
        return new RetryPolicy(maxAttempts, initialBackoff, initialBackoff.multipliedBy(16));
    }

    /** Delay before attempt {@code failedAttempt + 1}: initial, 2x, 4x... capped at maxBackoff. */
    public Duration backoffAfter(int failedAttempt) {
        long factor = 1L << Math.min(failedAttempt - 1, 20);
        Duration delay = initialBackoff.multipliedBy(factor);
        return delay.compareTo(maxBackoff) > 0 ? maxBackoff : delay;
    }
}
