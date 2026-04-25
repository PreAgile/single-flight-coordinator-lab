package com.portfolio.singleflight.coordinator.observability;

import java.util.Map;

/**
 * Thin metric port — abstracts over Micrometer / dropwizard / direct
 * Prometheus / no-op.
 *
 * <p>Decorators report metrics through this interface, never directly to a
 * specific backend. Consumers wire a backend-specific implementation (e.g.
 * Spring Boot's auto-configured {@code MeterRegistry}-backed sink) at the
 * application boundary.
 *
 * <p>This keeps {@code coordinator-core} framework-independent — Spring,
 * Quarkus, Micronaut, plain Tomcat 등 모두 같은 라이브러리를 쓸 수 있도록.
 *
 * <p>The default {@link #noop()} implementation is used in tests and
 * "metrics off" environments.
 */
public interface MetricSink {

    /** Counter increment by 1. */
    void incrementCounter(String name, Map<String, String> tags);

    /** Histogram (timer / distribution) record. */
    void recordHistogram(String name, double value, Map<String, String> tags);

    /** Gauge set (replace value). */
    void recordGauge(String name, double value, Map<String, String> tags);

    /** No-op sink for tests / disabled environments. */
    static MetricSink noop() {
        return NoopMetricSink.INSTANCE;
    }
}
