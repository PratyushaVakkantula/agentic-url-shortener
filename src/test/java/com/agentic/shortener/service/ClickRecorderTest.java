package com.agentic.shortener.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class ClickRecorderTest {

    /** Records batch sizes (the recorder reuses its list, so sizes are copied at call time). */
    static final class RecordingWriter extends ClickBatchWriter {
        final List<Integer> batchSizes = new CopyOnWriteArrayList<>();
        volatile boolean fail;

        RecordingWriter() {
            super(null, null);
        }

        @Override
        public void write(List<ClickCommand> batch) {
            batchSizes.add(batch.size());
            if (fail) {
                throw new IllegalStateException("db down");
            }
        }

        int total() {
            return batchSizes.stream().mapToInt(Integer::intValue).sum();
        }
    }

    private final RecordingWriter writer = new RecordingWriter();
    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    private ClickRecorder recorder;

    @AfterEach
    void stopWorker() {
        if (recorder != null && recorder.isRunning()) {
            recorder.stop();
        }
    }

    private ClickRecorder recorder(int capacity, int batchSize) {
        var props = new ShortenerRuntimeProperties(
                new ShortenerRuntimeProperties.CacheSettings(10, Duration.ofMinutes(1)),
                new ShortenerRuntimeProperties.ClickTrackingSettings(capacity, batchSize, Duration.ofMillis(20)));
        recorder = new ClickRecorder(writer, props, meters);
        return recorder;
    }

    private static ClickCommand click(long linkId) {
        return new ClickCommand(linkId, Instant.EPOCH, null, "Chrome");
    }

    private double counter(String name) {
        return meters.counter(name).count();
    }

    @Test
    void dropsAndCountsClicksWhenBufferIsFullWithoutBlocking() {
        ClickRecorder r = recorder(2, 10); // worker not started: nothing drains

        assertThat(r.record(click(1))).isTrue();
        assertThat(r.record(click(1))).isTrue();
        assertThat(r.record(click(1))).isFalse();

        assertThat(counter("shortener.clicks.dropped")).isEqualTo(1);
        assertThat(meters.get("shortener.clicks.queue.size").gauge().value()).isEqualTo(2);
        assertThat(writer.batchSizes).isEmpty();
    }

    @Test
    void flushWritesInBatchesOfConfiguredSize() {
        ClickRecorder r = recorder(100, 4);
        for (int i = 0; i < 10; i++) {
            r.record(click(i));
        }

        r.flush();

        assertThat(writer.batchSizes).containsExactly(4, 4, 2);
        assertThat(counter("shortener.clicks.recorded")).isEqualTo(10);
    }

    @Test
    void failedBatchIsCountedAndNotRetried() {
        ClickRecorder r = recorder(100, 50);
        writer.fail = true;
        r.record(click(1));
        r.record(click(2));

        r.flush();
        r.flush();

        assertThat(writer.batchSizes).containsExactly(2);
        assertThat(counter("shortener.clicks.failed")).isEqualTo(2);
        assertThat(counter("shortener.clicks.recorded")).isZero();
    }

    @Test
    void backgroundWorkerPersistsWithoutExplicitFlush() {
        ClickRecorder r = recorder(100, 50);
        r.start();

        r.record(click(1));
        r.record(click(2));

        awaitTotal(2, Duration.ofSeconds(5));
        assertThat(writer.total()).isEqualTo(2);
    }

    @Test
    void stopDrainsEverythingThatWasQueued() {
        ClickRecorder r = recorder(10_000, 7);
        for (int i = 0; i < 1_000; i++) {
            r.record(click(i));
        }
        r.start();

        r.stop(); // immediately: worker and final flush together must persist all 1000

        assertThat(writer.total()).isEqualTo(1_000);
        assertThat(writer.batchSizes).allMatch(size -> size <= 7);
        assertThat(r.isRunning()).isFalse();
    }

    /**
     * Regression for a starvation bug: the idle worker loops lock → poll(200 ms) → unlock → lock.
     * With a non-fair lock it re-acquired ahead of a waiting flush() almost every time; measured
     * worst case 66 s per flush (fair lock: 165 ms). The conditions matter: worker warmed up,
     * queue empty, flushes landing at arbitrary points in the poll cycle. Each flush is bounded
     * preemptively so the bug fails the test in seconds instead of hanging the build.
     */
    @Test
    void idleWorkerDoesNotStarveFlush() throws InterruptedException {
        var props = new ShortenerRuntimeProperties(
                new ShortenerRuntimeProperties.CacheSettings(10, Duration.ofMinutes(1)),
                new ShortenerRuntimeProperties.ClickTrackingSettings(100, 50, Duration.ofMillis(200)));
        recorder = new ClickRecorder(writer, props, meters);
        recorder.start();
        Thread.sleep(300); // let the worker enter its poll loop

        for (int i = 0; i < 5; i++) {
            Thread.sleep(37);
            assertTimeoutPreemptively(Duration.ofSeconds(2), recorder::flush,
                    "flush must wait at most ~one poll cycle");
        }
    }

    private void awaitTotal(int expected, Duration max) {
        long deadline = System.nanoTime() + max.toNanos();
        while (writer.total() < expected && System.nanoTime() < deadline) {
            Thread.onSpinWait();
        }
    }
}
