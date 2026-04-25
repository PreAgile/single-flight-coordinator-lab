package com.portfolio.singleflight.coordinator.integration;

import com.portfolio.singleflight.coordinator.SingleFlightCoordinator;
import com.portfolio.singleflight.coordinator.SingleFlightOptions;
import com.portfolio.singleflight.coordinator.adapter.InProcessSingleFlightCoordinator;
import com.portfolio.singleflight.coordinator.decorator.CapacityDecorator;
import com.portfolio.singleflight.coordinator.decorator.DeadlineDecorator;
import com.portfolio.singleflight.coordinator.decorator.HeartbeatDecorator;
import com.portfolio.singleflight.coordinator.decorator.TelemetryDecorator;
import com.portfolio.singleflight.coordinator.exception.CongestionException;
import com.portfolio.singleflight.coordinator.exception.DeadlineExceededException;
import com.portfolio.singleflight.coordinator.observability.MetricSink;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

/**
 * Integration tests for the full 5-layer decorator chain:
 *
 * <pre>
 * Telemetry → Heartbeat → Capacity → Deadline → InProcess (base)
 * </pre>
 *
 * <p>Validates that the layers compose correctly — exception classification,
 * cap enforcement, deadline propagation, and observability all hold under
 * realistic stack ordering. See ADR-003 for the rationale of this exact
 * layering.
 */
class FullChainTest {

    private InProcessSingleFlightCoordinator base;
    private HeartbeatDecorator heartbeat;
    private SingleFlightCoordinator chain;

    @BeforeEach
    void setUp() {
        base = new InProcessSingleFlightCoordinator();
        SingleFlightCoordinator deadline = new DeadlineDecorator(base, 200);
        SingleFlightCoordinator capacity = new CapacityDecorator(deadline, 5);
        heartbeat = new HeartbeatDecorator(
                capacity, Duration.ofMinutes(10), Duration.ofMinutes(1), MetricSink.noop());
        chain = new TelemetryDecorator(heartbeat, MetricSink.noop());
    }

    @AfterEach
    void tearDown() {
        heartbeat.close();
    }

    @Test
    @DisplayName("full chain: concurrent calls coalesce into a single inner invocation")
    void fullChainCoalesces() throws Exception {
        AtomicInteger invocations = new AtomicInteger();
        CompletableFuture<String> ownerFuture = new CompletableFuture<>();

        CompletableFuture<String> r1 = chain.execute("k", () -> {
            invocations.incrementAndGet();
            return ownerFuture;
        });
        CompletableFuture<String> r2 = chain.execute("k", () -> {
            invocations.incrementAndGet();
            return ownerFuture;
        });
        CompletableFuture<String> r3 = chain.execute("k", () -> {
            invocations.incrementAndGet();
            return ownerFuture;
        });

        await().atMost(1, TimeUnit.SECONDS)
                .until(() -> base.getInflightState().size() == 1
                        && base.getInflightState().get(0).waiterCount() == 3);

        ownerFuture.complete("shared");
        for (CompletableFuture<String> r : List.of(r1, r2, r3)) {
            assertThat(r.get(1, TimeUnit.SECONDS)).isEqualTo("shared");
        }
        assertThat(invocations.get()).as("operation invoked once across all callers").isEqualTo(1);
    }

    @Test
    @DisplayName("full chain: deadline triggers DeadlineExceededException for all coalesced callers")
    void fullChainDeadlineTriggersForAllCallers() {
        CompletableFuture<String> hanging = new CompletableFuture<>();

        CompletableFuture<String> r1 = chain.execute("k", () -> hanging);
        CompletableFuture<String> r2 = chain.execute("k", () -> hanging);

        for (CompletableFuture<String> r : List.of(r1, r2)) {
            assertThatThrownBy(() -> r.get(2, TimeUnit.SECONDS))
                    .isInstanceOf(ExecutionException.class)
                    .hasCauseInstanceOf(DeadlineExceededException.class);
        }
    }

    @Test
    @DisplayName("full chain: capacity rejects 6th attach (cap=5), existing 5 unaffected")
    void fullChainCapacityRejection() {
        // Use options to override the default 200ms deadline so the test is
        // about capacity, not timeout. Caller-supplied deadline of 5s.
        SingleFlightOptions longerDeadline = SingleFlightOptions.builder()
                .deadlineMs(5_000)
                .build();
        CompletableFuture<String> hanging = new CompletableFuture<>();

        // Owner + 4 waiters = 5 (cap)
        for (int i = 0; i < 5; i++) {
            chain.execute("k", () -> hanging, longerDeadline);
        }

        await().atMost(1, TimeUnit.SECONDS)
                .until(() -> base.getInflightState().size() == 1
                        && base.getInflightState().get(0).waiterCount() == 5);

        // 6th rejected
        assertThatThrownBy(() -> chain.execute("k", () -> hanging, longerDeadline))
                .isInstanceOf(CongestionException.class);

        // waiterCount must still be 5 (atomic guarantee)
        assertThat(base.getInflightState().get(0).waiterCount()).isEqualTo(5);

        hanging.complete("done");
    }

    @Test
    @DisplayName("full chain: cross-key isolation holds even with all decorators stacked")
    void fullChainCrossKeyIsolation() throws Exception {
        SingleFlightOptions longerDeadline = SingleFlightOptions.builder()
                .deadlineMs(5_000)
                .build();

        CompletableFuture<String> ownerA = new CompletableFuture<>();
        CompletableFuture<String> ownerB = new CompletableFuture<>();

        CompletableFuture<String> a = chain.execute("A", () -> ownerA, longerDeadline);
        CompletableFuture<String> b = chain.execute("B", () -> ownerB, longerDeadline);

        assertThat(base.getInflightState()).hasSize(2);

        ownerA.complete("a");
        ownerB.complete("b");

        assertThat(a.get(1, TimeUnit.SECONDS)).isEqualTo("a");
        assertThat(b.get(1, TimeUnit.SECONDS)).isEqualTo("b");
    }
}
