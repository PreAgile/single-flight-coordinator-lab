package com.portfolio.singleflight.coordinator.decorator;

import com.portfolio.singleflight.coordinator.InflightEntry;
import com.portfolio.singleflight.coordinator.SingleFlightCoordinator;
import com.portfolio.singleflight.coordinator.SingleFlightOptions;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

/**
 * Capacity decorator — applies a default {@code maxWaiters} cap to the
 * options if the caller didn't specify one, then delegates to the inner
 * coordinator (which atomically enforces the cap).
 *
 * <p><b>This decorator is intentionally thin.</b> The architectural decision
 * (see ADR-001 §"Capacity nuance" and DESIGN.md §"Decorator 2 — Capacity")
 * is that the actual race-free cap check happens INSIDE the base adapter's
 * {@code compute()} block. This decorator's job is to:
 * <ul>
 *   <li>Hold the default cap value (so the base adapter doesn't need
 *       per-call defaults baked in)</li>
 *   <li>Apply the default if the caller's options don't specify {@code
 *       maxWaiters}</li>
 *   <li>Make the policy boundary explicit and unit-testable</li>
 * </ul>
 *
 * <p>If decorator + atomicity sounds like SRP-tension: yes. We accept that
 * trade-off because race-free cap requires base-level enforcement
 * (otherwise check + attach is two phases). See ADR-001 §"When NOT to use".
 */
public final class CapacityDecorator implements SingleFlightCoordinator {

    private final SingleFlightCoordinator inner;
    private final int defaultMaxWaiters;

    /**
     * @param inner             the wrapped coordinator
     * @param defaultMaxWaiters cap to apply if the caller's options don't
     *                          set {@code maxWaiters}; use a non-positive
     *                          value to leave it unset (i.e. effectively
     *                          unlimited via {@code Integer.MAX_VALUE} in
     *                          the base)
     */
    public CapacityDecorator(SingleFlightCoordinator inner, int defaultMaxWaiters) {
        this.inner = Objects.requireNonNull(inner, "inner");
        this.defaultMaxWaiters = defaultMaxWaiters;
    }

    @Override
    public <T> CompletableFuture<T> execute(
            String key,
            Supplier<CompletableFuture<T>> operation,
            SingleFlightOptions options) {
        Objects.requireNonNull(options, "options");
        SingleFlightOptions effective = defaultMaxWaiters > 0
                ? options.orDefaultMaxWaiters(defaultMaxWaiters)
                : options;
        return inner.execute(key, operation, effective);
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
