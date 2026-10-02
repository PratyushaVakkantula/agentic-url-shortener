package com.agentic.shortener.service;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

/**
 * Takes click recording off the redirect path (NFR-10, A-12).
 *
 * <p>The request thread only does a non-blocking {@code offer} into a bounded queue. A single
 * background worker drains it in batches and hands them to {@link ClickBatchWriter}.
 *
 * <p>Failure semantics are explicit and observable:
 * <ul>
 *   <li><b>Queue full:</b> the click is dropped and {@code shortener.clicks.dropped} increments.
 *       Redirect latency is never traded for analytics completeness.</li>
 *   <li><b>DB write fails:</b> the batch is dropped and {@code shortener.clicks.failed}
 *       increments. No unbounded retry loop that could back up the queue.</li>
 *   <li><b>Shutdown:</b> the worker stops and the remaining queue is flushed before the
 *       database connection pool closes (lifecycle phase ordering).</li>
 * </ul>
 */
@Component
public class ClickRecorder implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(ClickRecorder.class);

    private final BlockingQueue<ClickCommand> queue;
    private final ClickBatchWriter writer;
    private final int batchSize;
    private final Duration flushInterval;
    private final Counter recorded;
    private final Counter dropped;
    private final Counter failed;
    /**
     * Serialises draining so {@link #flush()} returns only after in-flight batches are written.
     * <b>Must be fair:</b> the worker releases and immediately re-acquires this lock in a loop;
     * with a non-fair lock it barges ahead of a waiting {@code flush()} almost every time
     * (measured: up to 66 s per flush; fair: 165 ms). Fairness bounds a flush's wait to about one
     * poll cycle (flush-interval). Covered by ClickRecorderTest#idleWorkerDoesNotStarveFlush.
     */
    private final ReentrantLock drainLock = new ReentrantLock(true);

    private volatile boolean running;
    private Thread worker;

    public ClickRecorder(ClickBatchWriter writer, ShortenerRuntimeProperties properties, MeterRegistry meters) {
        var config = properties.clickTracking();
        this.queue = new ArrayBlockingQueue<>(config.queueCapacity());
        this.writer = writer;
        this.batchSize = config.batchSize();
        this.flushInterval = config.flushInterval();
        this.recorded = meters.counter("shortener.clicks.recorded");
        this.dropped = meters.counter("shortener.clicks.dropped");
        this.failed = meters.counter("shortener.clicks.failed");
        Gauge.builder("shortener.clicks.queue.size", queue, BlockingQueue::size).register(meters);
    }

    /** Never blocks. Returns false when the click was dropped because the buffer is full. */
    public boolean record(ClickCommand click) {
        if (queue.offer(click)) {
            return true;
        }
        dropped.increment();
        return false;
    }

    /** Synchronously writes everything currently buffered. Used on shutdown and by tests. */
    public void flush() {
        drainLock.lock();
        try {
            List<ClickCommand> batch = new ArrayList<>(batchSize);
            while (queue.drainTo(batch, batchSize) > 0) {
                writeBatch(batch);
                batch.clear();
            }
        } finally {
            drainLock.unlock();
        }
    }

    private void runWorker() {
        List<ClickCommand> batch = new ArrayList<>(batchSize);
        while (running) {
            drainLock.lock();
            try {
                ClickCommand first = queue.poll(flushInterval.toMillis(), TimeUnit.MILLISECONDS);
                if (first != null) {
                    batch.add(first);
                    queue.drainTo(batch, batchSize - 1);
                    writeBatch(batch);
                    batch.clear();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } finally {
                drainLock.unlock();
            }
        }
    }

    private void writeBatch(List<ClickCommand> batch) {
        try {
            writer.write(batch);
            recorded.increment(batch.size());
        } catch (RuntimeException e) {
            failed.increment(batch.size());
            log.error("Failed to persist {} click(s); batch dropped", batch.size(), e);
        }
    }

    @Override
    public void start() {
        running = true;
        worker = Thread.ofPlatform().name("click-recorder").daemon(true).start(this::runWorker);
    }

    @Override
    public void stop() {
        running = false;
        if (worker != null) {
            try {
                worker.join(flushInterval.plusSeconds(5).toMillis());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        flush();
        log.info("Click recorder stopped; queue drained");
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    /**
     * Lifecycle ordering: higher phases start last and stop first. The embedded web server runs
     * at {@code Integer.MAX_VALUE - 2048}; a lower phase here means the recorder starts before
     * traffic arrives and stops only <i>after</i> the server has stopped accepting requests, so
     * the final flush cannot miss late clicks. The DataSource is closed after all lifecycle
     * beans have stopped, so the flush still has a connection.
     */
    @Override
    public int getPhase() {
        return Integer.MAX_VALUE - 4096;
    }
}
