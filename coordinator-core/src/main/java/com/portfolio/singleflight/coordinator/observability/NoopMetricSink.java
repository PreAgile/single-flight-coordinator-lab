package com.portfolio.singleflight.coordinator.observability;

import java.util.Map;

/**
 * No-op {@link MetricSink} — discards all metric calls.
 *
 * <p>Default for tests and "metrics off" environments. Singleton.
 */
final class NoopMetricSink implements MetricSink {

    static final NoopMetricSink INSTANCE = new NoopMetricSink();

    private NoopMetricSink() {}

    @Override
    public void incrementCounter(String name, Map<String, String> tags) {
        // no-op
    }

    @Override
    public void recordHistogram(String name, double value, Map<String, String> tags) {
        // no-op
    }

    @Override
    public void recordGauge(String name, double value, Map<String, String> tags) {
        // no-op
    }
}
