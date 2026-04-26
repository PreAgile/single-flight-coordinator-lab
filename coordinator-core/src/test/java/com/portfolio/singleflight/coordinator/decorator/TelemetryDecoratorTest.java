package com.portfolio.singleflight.coordinator.decorator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.portfolio.singleflight.coordinator.SingleFlightCoordinator;
import com.portfolio.singleflight.coordinator.adapter.InProcessSingleFlightCoordinator;
import com.portfolio.singleflight.coordinator.exception.CongestionException;
import com.portfolio.singleflight.coordinator.exception.DeadlineExceededException;
import com.portfolio.singleflight.coordinator.observability.MetricSink;

class TelemetryDecoratorTest {

    @Test
    @DisplayName("success path records status=success in histogram")
    void successPathRecordsSuccess() throws Exception {
        InProcessSingleFlightCoordinator inner = new InProcessSingleFlightCoordinator();
        RecordingMetricSink metrics = new RecordingMetricSink();
        SingleFlightCoordinator coord = new TelemetryDecorator(inner, metrics);

        coord.execute("k", () -> CompletableFuture.completedFuture("v"))
                .get(1, TimeUnit.SECONDS);

        assertThat(metrics.statusOf("singleflight.owner_duration_ms")).isEqualTo("success");
    }

    @Test
    @DisplayName("failure path records status=failure")
    void failurePathRecordsFailure() {
        InProcessSingleFlightCoordinator inner = new InProcessSingleFlightCoordinator();
        RecordingMetricSink metrics = new RecordingMetricSink();
        SingleFlightCoordinator coord = new TelemetryDecorator(inner, metrics);

        CompletableFuture<String> result = coord.execute(
                "k", () -> CompletableFuture.failedFuture(new RuntimeException("oops")));

        assertThatThrownBy(() -> result.get(1, TimeUnit.SECONDS))
                .isInstanceOf(ExecutionException.class);
        assertThat(metrics.statusOf("singleflight.owner_duration_ms")).isEqualTo("failure");
    }

    @Test
    @DisplayName("deadline path classifies as status=deadline (Telemetry outermost sees DeadlineExceededException)")
    void deadlinePathRecordsDeadline() {
        InProcessSingleFlightCoordinator inner = new InProcessSingleFlightCoordinator();
        SingleFlightCoordinator withDeadline = new DeadlineDecorator(inner, 50);
        RecordingMetricSink metrics = new RecordingMetricSink();
        SingleFlightCoordinator coord = new TelemetryDecorator(withDeadline, metrics);

        CompletableFuture<String> result = coord.execute("k", () -> new CompletableFuture<>());

        assertThatThrownBy(() -> result.get(2, TimeUnit.SECONDS))
                .isInstanceOf(ExecutionException.class)
                .hasCauseInstanceOf(DeadlineExceededException.class);
        assertThat(metrics.statusOf("singleflight.owner_duration_ms")).isEqualTo("deadline");
    }

    @Test
    @DisplayName("congestion path classifies as status=congestion (sync throw from base)")
    void congestionPathRecordsCongestion() {
        InProcessSingleFlightCoordinator inner = new InProcessSingleFlightCoordinator();
        SingleFlightCoordinator withCapacity = new CapacityDecorator(inner, 1);
        RecordingMetricSink metrics = new RecordingMetricSink();
        SingleFlightCoordinator coord = new TelemetryDecorator(withCapacity, metrics);

        CompletableFuture<String> hanging = new CompletableFuture<>();
        coord.execute("k", () -> hanging); // owner — fills cap

        assertThatThrownBy(() -> coord.execute("k", () -> hanging))
                .isInstanceOf(CongestionException.class);
        assertThat(metrics.statusOf("singleflight.owner_duration_ms")).isEqualTo("congestion");

        hanging.complete("done");
    }

    @Test
    @DisplayName("histogram value is positive (duration recorded in millis)")
    void histogramRecordsPositiveDuration() throws Exception {
        InProcessSingleFlightCoordinator inner = new InProcessSingleFlightCoordinator();
        RecordingMetricSink metrics = new RecordingMetricSink();
        SingleFlightCoordinator coord = new TelemetryDecorator(inner, metrics);

        coord.execute("k", () -> CompletableFuture.completedFuture("v"))
                .get(1, TimeUnit.SECONDS);

        assertThat(metrics.lastValue("singleflight.owner_duration_ms")).isNotNegative();
    }

    // -------------------- helpers --------------------

    private static final class RecordingMetricSink implements MetricSink {
        private final List<RecordedHistogram> histograms = new ArrayList<>();

        @Override
        public synchronized void incrementCounter(String name, Map<String, String> tags) {}

        @Override
        public synchronized void recordHistogram(String name, double value, Map<String, String> tags) {
            histograms.add(new RecordedHistogram(name, value, tags));
        }

        @Override
        public synchronized void recordGauge(String name, double value, Map<String, String> tags) {}

        synchronized String statusOf(String metricName) {
            return histograms.stream()
                    .filter(h -> h.name.equals(metricName))
                    .reduce((first, second) -> second) // last wins
                    .map(h -> h.tags.getOrDefault("status", "<unknown>"))
                    .orElse("<not-recorded>");
        }

        synchronized double lastValue(String metricName) {
            AtomicReference<Double> last = new AtomicReference<>(-1.0);
            histograms.stream()
                    .filter(h -> h.name.equals(metricName))
                    .forEach(h -> last.set(h.value));
            return last.get();
        }
    }

    private record RecordedHistogram(String name, double value, Map<String, String> tags) {}
}
