package com.portfolio.singleflight.coordinator;

import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.OptionalLong;

/**
 * Per-call policy overrides for {@link SingleFlightCoordinator#execute}.
 *
 * <p>All fields are optional. Decorators apply their own defaults if a field
 * is empty. The base adapter only reads {@code maxWaiters} (for atomic
 * capacity enforcement); other fields are interpreted by decorators.
 *
 * <p>Use the builder for ergonomics:
 * <pre>{@code
 * SingleFlightOptions opts = SingleFlightOptions.builder()
 *     .deadlineMs(180_000)
 *     .maxWaiters(30)
 *     .telemetryTag("naver-ensure-session")
 *     .build();
 * }</pre>
 *
 * <p>Records are immutable; "with" methods return new instances.
 */
public record SingleFlightOptions(
        OptionalLong deadlineMs,
        OptionalInt maxWaiters,
        Optional<String> telemetryTag) {

    public SingleFlightOptions {
        Objects.requireNonNull(deadlineMs, "deadlineMs (use OptionalLong.empty())");
        Objects.requireNonNull(maxWaiters, "maxWaiters (use OptionalInt.empty())");
        Objects.requireNonNull(telemetryTag, "telemetryTag (use Optional.empty())");
    }

    /** Empty options — every field is unset. Decorators apply defaults. */
    public static SingleFlightOptions empty() {
        return new SingleFlightOptions(OptionalLong.empty(), OptionalInt.empty(), Optional.empty());
    }

    /**
     * Returns this options instance if {@code maxWaiters} is already set,
     * otherwise a copy with {@code defaultMax} applied.
     *
     * <p>Used by {@link com.portfolio.singleflight.coordinator.decorator.CapacityDecorator}
     * to apply its default without overriding caller-specified values.
     */
    public SingleFlightOptions orDefaultMaxWaiters(int defaultMax) {
        if (maxWaiters.isPresent()) {
            return this;
        }
        return new SingleFlightOptions(deadlineMs, OptionalInt.of(defaultMax), telemetryTag);
    }

    /**
     * Returns this options instance if {@code deadlineMs} is already set,
     * otherwise a copy with {@code defaultMs} applied.
     */
    public SingleFlightOptions orDefaultDeadlineMs(long defaultMs) {
        if (deadlineMs.isPresent()) {
            return this;
        }
        return new SingleFlightOptions(OptionalLong.of(defaultMs), maxWaiters, telemetryTag);
    }

    public static Builder builder() {
        return new Builder();
    }

    /** Fluent builder for ergonomic construction. */
    public static final class Builder {
        private OptionalLong deadlineMs = OptionalLong.empty();
        private OptionalInt maxWaiters = OptionalInt.empty();
        private Optional<String> telemetryTag = Optional.empty();

        private Builder() {}

        public Builder deadlineMs(long ms) {
            this.deadlineMs = OptionalLong.of(ms);
            return this;
        }

        public Builder maxWaiters(int max) {
            this.maxWaiters = OptionalInt.of(max);
            return this;
        }

        public Builder telemetryTag(String tag) {
            this.telemetryTag = Optional.ofNullable(tag);
            return this;
        }

        public SingleFlightOptions build() {
            return new SingleFlightOptions(deadlineMs, maxWaiters, telemetryTag);
        }
    }
}
