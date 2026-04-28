package com.portfolio.singleflight.coordinator;

import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.OptionalLong;

/**
 * Per-call policy overrides for {@link SingleFlightCoordinator#execute}.
 *
 * <p>All fields are optional. Decorators apply their own defaults if a field
 * is unset. The base adapter only reads {@code maxWaiters} (for atomic
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
 * <p>This class is immutable; "with default" methods return new instances.
 *
 * <p><b>Storage convention</b>: internal state uses nullable boxed types
 * ({@code Long}, {@code Integer}, {@code String}) so that "absent" has a
 * single representation ({@code null}). {@code Optional}/{@code OptionalLong}/
 * {@code OptionalInt} are exposed only on the public read API, per Java
 * convention (Optional is for return types, not field storage).
 */
public final class SingleFlightOptions {

    private final Long deadlineMs;
    private final Integer maxWaiters;
    private final String telemetryTag;

    private SingleFlightOptions(Long deadlineMs, Integer maxWaiters, String telemetryTag) {
        this.deadlineMs = deadlineMs;
        this.maxWaiters = maxWaiters;
        this.telemetryTag = telemetryTag;
    }

    /** Empty options — every field is unset. Decorators apply defaults. */
    public static SingleFlightOptions empty() {
        return new SingleFlightOptions(null, null, null);
    }

    public OptionalLong deadlineMs() {
        return deadlineMs == null ? OptionalLong.empty() : OptionalLong.of(deadlineMs);
    }

    public OptionalInt maxWaiters() {
        return maxWaiters == null ? OptionalInt.empty() : OptionalInt.of(maxWaiters);
    }

    public Optional<String> telemetryTag() {
        return Optional.ofNullable(telemetryTag);
    }

    /**
     * Returns this options instance if {@code maxWaiters} is already set,
     * otherwise a copy with {@code defaultMax} applied.
     *
     * <p>Used by {@link com.portfolio.singleflight.coordinator.decorator.CapacityDecorator}
     * to apply its default without overriding caller-specified values.
     */
    public SingleFlightOptions orDefaultMaxWaiters(int defaultMax) {
        if (maxWaiters != null) {
            return this;
        }
        return new SingleFlightOptions(deadlineMs, defaultMax, telemetryTag);
    }

    /**
     * Returns this options instance if {@code deadlineMs} is already set,
     * otherwise a copy with {@code defaultMs} applied.
     */
    public SingleFlightOptions orDefaultDeadlineMs(long defaultMs) {
        if (deadlineMs != null) {
            return this;
        }
        return new SingleFlightOptions(defaultMs, maxWaiters, telemetryTag);
    }

    public static Builder builder() {
        return new Builder();
    }

    /** Fluent builder for ergonomic construction. */
    public static final class Builder {
        private Long deadlineMs;
        private Integer maxWaiters;
        private String telemetryTag;

        private Builder() {}

        public Builder deadlineMs(long ms) {
            this.deadlineMs = ms;
            return this;
        }

        public Builder maxWaiters(int max) {
            this.maxWaiters = max;
            return this;
        }

        public Builder telemetryTag(String tag) {
            this.telemetryTag = tag;
            return this;
        }

        public SingleFlightOptions build() {
            return new SingleFlightOptions(deadlineMs, maxWaiters, telemetryTag);
        }
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof SingleFlightOptions that)) {
            return false;
        }
        return Objects.equals(deadlineMs, that.deadlineMs)
                && Objects.equals(maxWaiters, that.maxWaiters)
                && Objects.equals(telemetryTag, that.telemetryTag);
    }

    @Override
    public int hashCode() {
        return Objects.hash(deadlineMs, maxWaiters, telemetryTag);
    }

    @Override
    public String toString() {
        return "SingleFlightOptions["
                + "deadlineMs=" + deadlineMs()
                + ", maxWaiters=" + maxWaiters()
                + ", telemetryTag=" + telemetryTag()
                + ']';
    }
}
