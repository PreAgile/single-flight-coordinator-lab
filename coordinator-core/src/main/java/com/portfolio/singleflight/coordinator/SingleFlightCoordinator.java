package com.portfolio.singleflight.coordinator;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

/**
 * Per-key, once-at-a-time async execution with coalesced results.
 *
 * <p>Concurrent callers for the same {@code key} share the owner's
 * {@link CompletableFuture}; subsequent callers attach as waiters and receive
 * the same resolved/rejected value.
 *
 * <p>Implementations decide where coalescing happens (in-process Map, Redis
 * distributed lock, MQ routing). Domain services (e.g. {@code NaverService})
 * depend only on this Port; backend swap is a DI-wiring change, not a
 * service rewrite.
 *
 * <p>See ADR-001 for the rationale of the Port/Adapter (Hexagonal) pattern
 * choice.
 */
public interface SingleFlightCoordinator {

    /**
     * Coalesce concurrent calls for the same {@code key} using default
     * (empty) options. Convenience overload.
     */
    default <T> CompletableFuture<T> execute(String key, Supplier<CompletableFuture<T>> operation) {
        return execute(key, operation, SingleFlightOptions.empty());
    }

    /**
     * Coalesce concurrent calls for the same {@code key}.
     *
     * <p>The first caller for a given key becomes the owner and executes
     * {@code operation}; subsequent callers wait for the owner's result and
     * receive it (success or failure) as their own future's outcome.
     *
     * <p>Options are advisory — the base adapter may interpret only those it
     * understands (e.g. {@code maxWaiters} for atomic capacity enforcement),
     * leaving others to decorators.
     *
     * @param key       coalescing key (e.g. user id, cache key)
     * @param operation lazy supplier of the work to perform; invoked at most
     *                  once per inflight period
     * @param options   per-call policy overrides; never {@code null}
     * @return the future to be shared with all coalesced waiters
     */
    <T> CompletableFuture<T> execute(
            String key,
            Supplier<CompletableFuture<T>> operation,
            SingleFlightOptions options);

    /**
     * Snapshot of currently inflight entries — observability only.
     *
     * <p>The list is a point-in-time copy; iteration is not guaranteed to
     * reflect changes made during iteration. Used by heartbeat/health probes
     * and ops tooling.
     */
    List<InflightEntry> getInflightState();

    /**
     * Ops kill-switch: abandon the owner's future, fail all waiters with
     * {@code ForceReleasedException}, and remove the entry so the next caller
     * starts fresh.
     *
     * <p>The underlying operation is NOT cancelled — only the future is
     * completed exceptionally. Resource cleanup of the underlying work is
     * the caller's responsibility (see DESIGN.md for the timeout vs
     * cancellation discussion).
     *
     * @param key    coalescing key to release
     * @param reason human-readable reason for telemetry / logs
     * @return future that completes when the release signal has been recorded
     */
    CompletableFuture<Void> forceRelease(String key, String reason);
}
