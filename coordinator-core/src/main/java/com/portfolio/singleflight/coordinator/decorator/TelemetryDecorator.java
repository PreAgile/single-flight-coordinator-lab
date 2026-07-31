package com.portfolio.singleflight.coordinator.decorator;

import com.portfolio.singleflight.coordinator.InflightEntry;
import com.portfolio.singleflight.coordinator.SingleFlightCoordinator;
import com.portfolio.singleflight.coordinator.SingleFlightOptions;
import com.portfolio.singleflight.coordinator.exception.CongestionException;
import com.portfolio.singleflight.coordinator.exception.DeadlineExceededException;
import com.portfolio.singleflight.coordinator.exception.ForceReleasedException;
import com.portfolio.singleflight.coordinator.observability.MetricSink;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.function.Supplier;

/**
 * Telemetry decorator — outermost layer in the canonical stack.
 *
 * <p><b>Why outermost?</b> So it sees the FINAL exception type after all
 * other decorators have processed (e.g.
 * {@link DeadlineExceededException} thrown by {@link DeadlineDecorator})
 * and can classify {@code status=deadline} correctly. If telemetry sat
 * inside the deadline decorator, it would only see the raw underlying
 * exception (or a hang) and lose classification.
 *
 * <h2>Emitted signals</h2>
 * <ul>
 *   <li>{@code singleflight.owner_duration_ms} (histogram) — wall-clock
 *       duration from {@code execute} entry to future settlement, tagged
 *       with {@code status} (success / failure / deadline / congestion /
 *       force_released)</li>
 *   <li>Structured log lines for each lifecycle event:
 *       {@code event=owner_started}, {@code event=owner_finished
 *       status=...}</li>
 * </ul>
 *
 * <p>See ADR-003 (decorator stack order) for the rationale.
 */
public final class TelemetryDecorator implements SingleFlightCoordinator {

    private static final Logger log = LoggerFactory.getLogger(TelemetryDecorator.class);

    private final SingleFlightCoordinator inner;
    private final MetricSink metrics;

    public TelemetryDecorator(SingleFlightCoordinator inner, MetricSink metrics) {
        this.inner = Objects.requireNonNull(inner, "inner");
        this.metrics = Objects.requireNonNull(metrics, "metrics");
    }

    @Override
    public <T> CompletableFuture<T> execute(
            String key,
            Supplier<CompletableFuture<T>> operation,
            SingleFlightOptions options) {
        long start = System.currentTimeMillis();
        String tag = options.telemetryTag().orElse("default");

        log.info("[SingleFlight] tag={} event=owner_started key={}", tag, key);

        CompletableFuture<T> result;
        try {
            result = inner.execute(key, operation, options);
        } catch (CongestionException e) {
            // Synchronous throw from atomic capacity check (base adapter).
            // Record + propagate.
            recordOutcome(key, tag, start, e);
            throw e;
        }

        return result.whenComplete((v, e) -> recordOutcome(key, tag, start, e));
    }

    private void recordOutcome(String key, String tag, long startMs, Throwable error) {
        long durationMs = System.currentTimeMillis() - startMs;
        String status = classifyStatus(error);
        Map<String, String> tags = Map.of("status", status, "tag", tag);

        metrics.recordHistogram("singleflight.owner_duration_ms", (double) durationMs, tags);

        if (error == null) {
            log.info(
                    "[SingleFlight] tag={} event=owner_finished status=success key={} durationMs={}",
                    tag, key, durationMs);
        } else {
            Throwable cause = unwrap(error);
            log.warn(
                    "[SingleFlight] tag={} event=owner_finished status={} key={} durationMs={} errorClass={}",
                    tag, status, key, durationMs, cause.getClass().getSimpleName());
        }
    }

    private static String classifyStatus(Throwable error) {
        if (error == null) {
            return "success";
        }
        Throwable cause = unwrap(error);
        if (cause instanceof DeadlineExceededException) {
            return "deadline";
        }
        if (cause instanceof CongestionException) {
            return "congestion";
        }
        if (cause instanceof ForceReleasedException) {
            return "force_released";
        }
        return "failure";
    }

    private static Throwable unwrap(Throwable t) {
        Throwable cause = t;
        while (cause.getCause() != null
                && (cause instanceof CompletionException || cause instanceof ExecutionException)) {
            cause = cause.getCause();
        }
        return cause;
    }

    @Override
    public List<InflightEntry> getInflightState() {
        return inner.getInflightState();
    }

    @Override
    public CompletableFuture<Void> forceRelease(String key, String reason) {
        return inner.forceRelease(key, reason);
    }
}
