package com.agentic.platform.web.ratelimit;

/**
 * Classic token bucket with continuous refill. Allows short bursts up to {@code capacity}
 * while enforcing an average of {@code capacity / refillPeriod}. Time is passed in (not read)
 * so behaviour is deterministic under test.
 *
 * <p>Uses exact integer arithmetic: the level is kept in "token-milliseconds" (one token =
 * {@code refillPeriodMillis} units, and each elapsed millisecond adds {@code capacity} units).
 * Floating point would make the refill boundary drift by rounding errors.
 *
 * <p>Thread-safe: one bucket is shared by all concurrent requests from the same client.
 */
public final class TokenBucket {

    private final long capacity;
    private final long periodMillis;
    private final long maxUnits;
    private long units;
    private long lastRefillMillis;

    public TokenBucket(long capacity, long refillPeriodMillis, long nowMillis) {
        if (capacity <= 0 || refillPeriodMillis <= 0) {
            throw new IllegalArgumentException("capacity and refill period must be positive");
        }
        this.capacity = capacity;
        this.periodMillis = refillPeriodMillis;
        this.maxUnits = Math.multiplyExact(capacity, refillPeriodMillis);
        this.units = maxUnits;
        this.lastRefillMillis = nowMillis;
    }

    public synchronized Result tryConsume(long nowMillis) {
        refill(nowMillis);
        if (units >= periodMillis) {
            units -= periodMillis;
            return new Result(true, units / periodMillis, 0);
        }
        long missingUnits = periodMillis - units;
        long millisUntilToken = (missingUnits + capacity - 1) / capacity;
        return new Result(false, 0, Math.max(1, (millisUntilToken + 999) / 1000));
    }

    public long capacity() {
        return capacity;
    }

    private void refill(long nowMillis) {
        long elapsed = nowMillis - lastRefillMillis;
        if (elapsed <= 0) {
            return; // tolerate clock going backwards: no refill, no penalty
        }
        // A full period refills completely; checking first also avoids overflow on long idles.
        units = elapsed >= periodMillis ? maxUnits : Math.min(maxUnits, units + elapsed * capacity);
        lastRefillMillis = nowMillis;
    }

    /**
     * @param remaining         whole tokens left after this request
     * @param retryAfterSeconds when rejected, seconds until one token is available (≥ 1)
     */
    public record Result(boolean allowed, long remaining, long retryAfterSeconds) {
    }
}
