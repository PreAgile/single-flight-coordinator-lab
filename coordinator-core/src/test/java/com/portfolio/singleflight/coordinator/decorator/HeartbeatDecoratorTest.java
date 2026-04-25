package com.portfolio.singleflight.coordinator.decorator;

import com.portfolio.singleflight.coordinator.SingleFlightCoordinator;
import com.portfolio.singleflight.coordinator.adapter.InProcessSingleFlightCoordinator;
import com.portfolio.singleflight.coordinator.observability.MetricSink;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

class HeartbeatDecoratorTest {

    private InProcessSingleFlightCoordinator base;
    private RecordingMetricSink metrics;
    private HeartbeatDecorator heartbeat;

    @BeforeEach
    void setUp() {
        base = new InProcessSingleFlightCoordinator();
        metrics = new RecordingMetricSink();
    }

    @AfterEach
    void tearDown() {
        if (heartbeat != null) {
            heartbeat.close();
        }
    }

    @Test
    @DisplayName("execute() is pass-through — heartbeat does not interfere with results")
    void executeIsPassThrough() throws Exception {
        // Long interval so the periodic scan doesn't fire during this test.
        heartbeat = new HeartbeatDecorator(
                base, Duration.ofMinutes(10), Duration.ofMinutes(1), metrics);

        String result = heartbeat.execute("k", () -> CompletableFuture.completedFuture("v"))
                .get(1, TimeUnit.SECONDS);

        assertThat(result).isEqualTo("v");
    }

    @Test
    @DisplayName("scan() emits long_running counter when entry age >= threshold")
    void scanEmitsLongRunningCounter() {
        // Threshold 0 — every entry is long-running for the purposes of the test.
        heartbeat = new HeartbeatDecorator(
                base, Duration.ofMinutes(10), Duration.ZERO, metrics);

        CompletableFuture<String> hanging = new CompletableFuture<>();
        heartbeat.execute("stuck", () -> hanging);

        // Trigger a scan manually (deterministic — no waiting on the timer).
        heartbeat.scan();

        await().atMost(500, TimeUnit.MILLISECONDS).untilAsserted(() ->
                assertThat(metrics.counterCalls("singleflight.long_running")).isPositive());
        assertThat(metrics.gaugeLast("singleflight.inflight_count")).isEqualTo(1.0);

        hanging.complete("done");
    }

    @Test
    @DisplayName("close() cancels the periodic timer cleanly")
    void closeStopsScheduler() {
        heartbeat = new HeartbeatDecorator(
                base, Duration.ofMillis(20), Duration.ofMinutes(1), metrics);

        // Let it tick a few times.
        await().atMost(500, TimeUnit.MILLISECONDS).untilAsserted(() ->
                assertThat(metrics.gaugeCalls("singleflight.inflight_count")).isGreaterThanOrEqualTo(2));

        long countAtClose = metrics.gaugeCalls("singleflight.inflight_count");
        heartbeat.close();

        // After close, no more ticks should land. Allow a short settling
        // interval (timer might fire concurrently with close) and then assert
        // stability.
        await().pollDelay(Duration.ofMillis(200)).atMost(500, TimeUnit.MILLISECONDS)
                .untilAsserted(() -> {
                    long after = metrics.gaugeCalls("singleflight.inflight_count");
                    assertThat(after - countAtClose)
                            .as("no more ticks after close (allow at most 1 in-flight)")
                            .isLessThanOrEqualTo(1);
                });
    }

    // -------------------- helpers --------------------

    private static final class RecordingMetricSink implements MetricSink {
        private final List<String> counters = new ArrayList<>();
        private final List<Double> gauges = new ArrayList<>();
        private double lastGaugeValue;

        @Override
        public synchronized void incrementCounter(String name, Map<String, String> tags) {
            counters.add(name);
        }

        @Override
        public synchronized void recordHistogram(String name, double value, Map<String, String> tags) {
            // unused in heartbeat
        }

        @Override
        public synchronized void recordGauge(String name, double value, Map<String, String> tags) {
            gauges.add(value);
            lastGaugeValue = value;
        }

        synchronized long counterCalls(String name) {
            return counters.stream().filter(name::equals).count();
        }

        synchronized long gaugeCalls(String ignoredName) {
            return gauges.size();
        }

        synchronized double gaugeLast(String ignoredName) {
            return lastGaugeValue;
        }
    }
}
