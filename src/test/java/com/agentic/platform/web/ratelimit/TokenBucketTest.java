package com.agentic.platform.web.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class TokenBucketTest {

    private static final long MINUTE = 60_000;

    @Test
    void allowsBurstUpToCapacityThenRejects() {
        TokenBucket bucket = new TokenBucket(3, MINUTE, 0);

        assertThat(bucket.tryConsume(0)).isEqualTo(new TokenBucket.Result(true, 2, 0));
        assertThat(bucket.tryConsume(0).remaining()).isEqualTo(1);
        assertThat(bucket.tryConsume(0).remaining()).isZero();

        TokenBucket.Result rejected = bucket.tryConsume(0);
        assertThat(rejected.allowed()).isFalse();
        assertThat(rejected.retryAfterSeconds()).isEqualTo(20); // 3 per minute = one token every 20s
    }

    @Test
    void refillsContinuouslyButNeverAboveCapacity() {
        TokenBucket bucket = new TokenBucket(3, MINUTE, 0);
        for (int i = 0; i < 3; i++) {
            bucket.tryConsume(0);
        }

        assertThat(bucket.tryConsume(19_999).allowed()).isFalse();
        assertThat(bucket.tryConsume(20_000).allowed()).isTrue();

        // A long idle period refills to capacity, not beyond it.
        assertThat(bucket.tryConsume(10 * MINUTE).remaining()).isEqualTo(2);
    }

    @Test
    void toleratesClockGoingBackwards() {
        TokenBucket bucket = new TokenBucket(1, MINUTE, 50_000);
        bucket.tryConsume(50_000);

        assertThat(bucket.tryConsume(10_000).allowed()).isFalse();
        assertThat(bucket.tryConsume(110_000).allowed()).isTrue();
    }
}
