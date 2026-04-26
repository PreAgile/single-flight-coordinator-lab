package com.portfolio.singleflight.coordinator.decorator;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;

import com.portfolio.singleflight.coordinator.InflightEntry;
import com.portfolio.singleflight.coordinator.SingleFlightCoordinator;
import com.portfolio.singleflight.coordinator.SingleFlightOptions;
import com.portfolio.singleflight.coordinator.exception.DeadlineExceededException;

/**
 * Deadline decorator — wraps the operation with a wall-clock timeout and
 * translates {@link TimeoutException} into our domain-specific {@link
 * DeadlineExceededException}.
 *
 * <h2>Important nuance — future timeout vs work cancellation</h2>
 *
 * <p>This decorator uses {@link CompletableFuture#orTimeout(long,
 * TimeUnit)}, which:
 * <ul>
 *   <li>✅ Completes the returned future exceptionally with
 *       {@link TimeoutException} after the deadline</li>
 *   <li>✅ All coalesced callers (owner + waiters) see the failure (since
 *       they share the same future)</li>
 *   <li>❌ Does NOT cancel the underlying work (e.g. a Playwright session,
 *       a Redis call). The orphaned operation continues running in the
 *       background.</li>
 * </ul>
 *
 * <p>Resource cleanup of the underlying work is the operation author's
 * responsibility — typically via try-finally or {@code whenComplete} on the
 * underlying operation's future.
 *
 * <p>See DESIGN.md §"Decorator 1 — Deadline" for the full discussion.
 */
public final class DeadlineDecorator implements SingleFlightCoordinator {

    private final SingleFlightCoordinator inner;
    /** Default deadline in millis; 0 or negative means disabled. */
    private final long defaultDeadlineMs;

    public DeadlineDecorator(SingleFlightCoordinator inner, long defaultDeadlineMs) {
        this.inner = Objects.requireNonNull(inner, "inner");
        this.defaultDeadlineMs = defaultDeadlineMs;
    }

    @Override
    public <T> CompletableFuture<T> execute(
            String key,
            Supplier<CompletableFuture<T>> operation,
            SingleFlightOptions options) {
        long effectiveDeadline = options.deadlineMs().orElse(defaultDeadlineMs);
        if (effectiveDeadline <= 0) {
            // Disabled — pass through.
            return inner.execute(key, operation, options);
        }

        SingleFlightOptions effectiveOptions = options.orDefaultDeadlineMs(effectiveDeadline);
        String tag = effectiveOptions.telemetryTag().orElse(null);

        // Wrap the user-supplied operation with the timeout, then translate
        // TimeoutException into our domain exception (so Telemetry can
        // classify status=deadline correctly).
        Supplier<CompletableFuture<T>> wrapped = () -> {
            CompletableFuture<T> opFuture;
            try {
                opFuture = operation.get();
            } catch (Throwable t) {
                return CompletableFuture.failedFuture(t);
            }
            if (opFuture == null) {
                return CompletableFuture.failedFuture(
                        new IllegalStateException("operation supplier returned null"));
            }
            return opFuture
                    .orTimeout(effectiveDeadline, TimeUnit.MILLISECONDS)
                    .exceptionallyCompose(throwable -> {
                        Throwable cause = unwrap(throwable);
                        if (cause instanceof TimeoutException) {
                            return CompletableFuture.failedFuture(
                                    new DeadlineExceededException(key, effectiveDeadline, tag, cause));
                        }
                        return CompletableFuture.failedFuture(throwable);
                    });
        };

        return inner.execute(key, wrapped, effectiveOptions);
    }

    @Override
    public List<InflightEntry> getInflightState() {
        return inner.getInflightState();
    }

    @Override
    public CompletableFuture<Void> forceRelease(String key, String reason) {
        return inner.forceRelease(key, reason);
    }

    private static Throwable unwrap(Throwable t) {
        // CompletableFuture wraps async errors in CompletionException.
        Throwable cause = t;
        while (cause.getCause() != null
                && (cause instanceof java.util.concurrent.CompletionException
                        || cause instanceof java.util.concurrent.ExecutionException)) {
            cause = cause.getCause();
        }
        return cause;
    }
}
