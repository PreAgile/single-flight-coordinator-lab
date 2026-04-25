package com.portfolio.singleflight.coordinator.adapter;

import com.portfolio.singleflight.coordinator.InflightEntry;
import com.portfolio.singleflight.coordinator.SingleFlightCoordinator;
import com.portfolio.singleflight.coordinator.SingleFlightOptions;
import com.portfolio.singleflight.coordinator.exception.CongestionException;
import com.portfolio.singleflight.coordinator.exception.ForceReleasedException;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

/**
 * In-process Map-based coordinator (Issue #2 / Phase 1).
 *
 * <p><b>Key design:</b> the entry attach + capacity check happens inside a
 * {@link ConcurrentHashMap#compute(Object, java.util.function.BiFunction)}
 * block, which is atomic per-key. This is the resolution to the review
 * feedback that "two-phase capacity check is racy" (see ADR-001 §"Decision
 * Signals" and DESIGN.md §"Capacity").
 *
 * <h2>Atomicity guarantee</h2>
 * <ul>
 *   <li>{@code compute()} holds an exclusive bin lock for the key while the
 *       lambda runs. No other thread can attach, detach, or read the same key
 *       concurrently.</li>
 *   <li>Capacity check ({@code waiterCount.get() >= max}) and increment
 *       ({@code waiterCount.incrementAndGet()}) both happen inside the lambda
 *       — race-free.</li>
 *   <li>If the lambda throws (e.g. {@link CongestionException}), the mapping
 *       is left unchanged (per {@code ConcurrentHashMap.compute} spec). Crucially,
 *       we DO NOT mutate {@code waiterCount} before the cap check, so a thrown
 *       capacity error doesn't leave a stale increment.</li>
 * </ul>
 *
 * <h2>Cleanup</h2>
 * <p>Each owner's future has a {@code whenComplete} hook that removes its
 * record from the map (using a second {@code compute} guarded by identity
 * check, so it doesn't accidentally remove a successor that took the slot
 * after a force-release).
 *
 * <h2>Single-instance only</h2>
 * <p>This adapter coalesces within a single JVM. For multi-instance
 * environments, see {@code RedisSingleFlightCoordinator} (Phase 3, ADR-002).
 */
public final class InProcessSingleFlightCoordinator implements SingleFlightCoordinator {

    private static final String HOST_ID = computeHostId();

    private final ConcurrentHashMap<String, InflightRecord<?>> inflight = new ConcurrentHashMap<>();

    @Override
    public <T> CompletableFuture<T> execute(
            String key,
            Supplier<CompletableFuture<T>> operation,
            SingleFlightOptions options) {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(operation, "operation");
        Objects.requireNonNull(options, "options");

        int maxWaiters = options.maxWaiters().orElse(Integer.MAX_VALUE);
        AtomicReference<CompletableFuture<T>> resultRef = new AtomicReference<>();
        AtomicReference<CompletableFuture<T>> newOwnerRef = new AtomicReference<>();

        inflight.compute(key, (k, existing) -> {
            if (existing != null) {
                // Coalesce path — attach as waiter.
                int currentWaiters = existing.waiterCount.get();
                if (currentWaiters >= maxWaiters) {
                    // Atomic capacity rejection. Throwing here leaves the
                    // mapping unchanged per ConcurrentHashMap.compute spec.
                    // We have NOT mutated waiterCount yet, so no rollback
                    // needed.
                    throw new CongestionException(
                            k,
                            currentWaiters,
                            maxWaiters,
                            options.telemetryTag().orElse(null));
                }
                existing.waiterCount.incrementAndGet();
                @SuppressWarnings("unchecked")
                InflightRecord<T> typed = (InflightRecord<T>) existing;
                resultRef.set(typed.future);
                return existing;
            }

            // Owner path — start the operation.
            CompletableFuture<T> future;
            try {
                CompletableFuture<T> supplied = operation.get();
                future = supplied != null
                        ? supplied
                        : CompletableFuture.failedFuture(
                                new IllegalStateException("operation supplier returned null"));
            } catch (Throwable t) {
                future = CompletableFuture.failedFuture(t);
            }

            InflightRecord<T> record = new InflightRecord<>(
                    key, future, System.currentTimeMillis(), new AtomicInteger(1));
            resultRef.set(future);
            newOwnerRef.set(future);
            return record;
        });

        // Register the cleanup hook AFTER the record is safely in the map.
        // If we registered inside the compute block, an already-completed
        // future (e.g. completedFuture / failedFuture from a sync supplier)
        // would fire whenComplete immediately on the registering thread —
        // before the record was inserted — so the evict would be a no-op
        // and the record would leak. Registering here guarantees the record
        // is visible when the hook runs.
        CompletableFuture<T> newOwner = newOwnerRef.get();
        if (newOwner != null) {
            newOwner.whenComplete((v, e) -> evictIfStillOwner(key, newOwner));
        }

        CompletableFuture<T> result = resultRef.get();
        if (result == null) {
            // Defensive — should not happen given the compute block above.
            return CompletableFuture.failedFuture(
                    new IllegalStateException("compute block did not produce a future"));
        }
        return result;
    }

    @Override
    public List<InflightEntry> getInflightState() {
        // Snapshot — point-in-time copy. May not reflect concurrent mutations.
        List<InflightEntry> snapshot = new ArrayList<>(inflight.size());
        inflight.forEach((k, record) -> snapshot.add(new InflightEntry(
                k, record.startedAt, record.waiterCount.get(), HOST_ID)));
        return Collections.unmodifiableList(snapshot);
    }

    @Override
    public CompletableFuture<Void> forceRelease(String key, String reason) {
        Objects.requireNonNull(key, "key");
        InflightRecord<?> removed = inflight.remove(key);
        if (removed != null) {
            ForceReleasedException ex = new ForceReleasedException(key, reason);
            // Complete exceptionally — propagates to all coalesced callers.
            // The whenComplete hook above will attempt to evict, but the
            // identity check (current.future == ownerFuture) will fail since
            // we've already removed it, so it's a no-op. Safe.
            removed.future.completeExceptionally(ex);
        }
        return CompletableFuture.completedFuture(null);
    }

    private void evictIfStillOwner(String key, CompletableFuture<?> ownerFuture) {
        inflight.compute(key, (k, current) -> {
            if (current != null && current.future == ownerFuture) {
                return null; // remove
            }
            return current; // keep — someone else owns the slot now
        });
    }

    /**
     * Internal mapping value — the owner future + per-key state.
     *
     * <p>Mutable {@code waiterCount} is safe because all reads/writes happen
     * inside {@code ConcurrentHashMap.compute()} blocks (atomic per key) or
     * via {@code AtomicInteger} for snapshot reads (e.g. observability via
     * {@link #getInflightState()}).
     */
    private static final class InflightRecord<T> {
        final String key;
        final CompletableFuture<T> future;
        final long startedAt;
        final AtomicInteger waiterCount;

        InflightRecord(String key, CompletableFuture<T> future, long startedAt, AtomicInteger waiterCount) {
            this.key = key;
            this.future = future;
            this.startedAt = startedAt;
            this.waiterCount = waiterCount;
        }
    }

    private static String computeHostId() {
        try {
            return InetAddress.getLocalHost().getHostName();
        } catch (UnknownHostException e) {
            return "unknown";
        }
    }
}
