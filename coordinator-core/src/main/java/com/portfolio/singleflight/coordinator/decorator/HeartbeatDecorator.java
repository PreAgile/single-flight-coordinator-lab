package com.portfolio.singleflight.coordinator.decorator;

import com.portfolio.singleflight.coordinator.InflightEntry;
import com.portfolio.singleflight.coordinator.SingleFlightCoordinator;
import com.portfolio.singleflight.coordinator.SingleFlightOptions;
import com.portfolio.singleflight.coordinator.observability.MetricSink;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

/**
 * Heartbeat decorator — periodically scans inflight entries and warns about
 * long-running owners (potential stuck operations).
 *
 * <p>Pure pass-through for {@code execute()} — the heartbeat work happens
 * on a separate scheduled timer thread. This decorator's value is purely
 * observability:
 * <ul>
 *   <li><b>Early warning</b>: if an owner has been running for &gt; threshold
 *       (e.g. 60s when deadline is 180s), operators get a warn log before
 *       the deadline fires. Manual intervention (forceRelease) becomes
 *       possible.</li>
 *   <li><b>Metrics</b>: emits a {@code singleflight.long_running} counter
 *       per warning, plus a gauge of total inflight count.</li>
 * </ul>
 *
 * <p><b>Lifecycle:</b> the scheduler is a daemon thread, so it doesn't
 * prevent JVM shutdown. Call {@link #close()} for explicit cleanup (e.g. in
 * test teardown or DI container destroy hooks).
 */
public final class HeartbeatDecorator implements SingleFlightCoordinator, AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(HeartbeatDecorator.class);

    private final SingleFlightCoordinator inner;
    private final ScheduledExecutorService scheduler;
    private final long longRunningThresholdMs;
    private final MetricSink metrics;
    private final ScheduledFuture<?> task;
    private final AtomicBoolean closed = new AtomicBoolean(false);

    public HeartbeatDecorator(
            SingleFlightCoordinator inner,
            Duration scanInterval,
            Duration longRunningThreshold,
            MetricSink metrics) {
        this.inner = Objects.requireNonNull(inner, "inner");
        Objects.requireNonNull(scanInterval, "scanInterval");
        Objects.requireNonNull(longRunningThreshold, "longRunningThreshold");
        this.longRunningThresholdMs = longRunningThreshold.toMillis();
        this.metrics = Objects.requireNonNull(metrics, "metrics");
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "singleflight-heartbeat");
            t.setDaemon(true);
            return t;
        });
        long intervalMs = scanInterval.toMillis();
        this.task = scheduler.scheduleAtFixedRate(this::scan, intervalMs, intervalMs, TimeUnit.MILLISECONDS);
    }

    @Override
    public <T> CompletableFuture<T> execute(
            String key,
            Supplier<CompletableFuture<T>> operation,
            SingleFlightOptions options) {
        // Pure pass-through. Observability lives in the scheduled scan.
        return inner.execute(key, operation, options);
    }

    @Override
    public List<InflightEntry> getInflightState() {
        return inner.getInflightState();
    }

    @Override
    public CompletableFuture<Void> forceRelease(String key, String reason) {
        return inner.forceRelease(key, reason);
    }

    /** Single scan pass — exposed for tests (deterministic invocation). */
    void scan() {
        try {
            List<InflightEntry> snapshot = inner.getInflightState();
            metrics.recordGauge("singleflight.inflight_count", snapshot.size(), Map.of());

            long now = System.currentTimeMillis();
            for (InflightEntry entry : snapshot) {
                long ageMs = now - entry.startedAt();
                if (ageMs >= longRunningThresholdMs) {
                    log.warn(
                            "[SingleFlight] long_running detected key={} ageMs={} waiterCount={} hostId={}",
                            entry.key(), ageMs, entry.waiterCount(), entry.hostId());
                    metrics.incrementCounter(
                            "singleflight.long_running",
                            Map.of("hostId", entry.hostId()));
                }
            }
        } catch (Throwable t) {
            // Heartbeat failure must NOT propagate — it's observability,
            // not the critical path.
            log.error("[SingleFlight] heartbeat scan failed", t);
        }
    }

    @Override
    public void close() {
        if (closed.compareAndSet(false, true)) {
            task.cancel(false);
            scheduler.shutdown();
        }
    }
}
