package com.portfolio.singleflight.coordinator.adapter;

import com.portfolio.singleflight.coordinator.SingleFlightCoordinator;
import com.portfolio.singleflight.coordinator.SingleFlightOptions;
import com.portfolio.singleflight.coordinator.exception.CongestionException;
import com.portfolio.singleflight.coordinator.exception.ForceReleasedException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

/**
 * Unit tests for {@link InProcessSingleFlightCoordinator}.
 *
 * <p>Covers the 7 scenarios listed in Issue #2 plus atomic capacity (Phase 1
 * 의 핵심 보강 포인트, ADR-001 review 후속).
 */
class InProcessSingleFlightCoordinatorTest {

    private SingleFlightCoordinator coordinator;

    @BeforeEach
    void setUp() {
        coordinator = new InProcessSingleFlightCoordinator();
    }

    // ------------------------------------------------------------------
    // 1. Single call → owner result
    // ------------------------------------------------------------------
    @Test
    @DisplayName("single call returns the owner future's value")
    void singleCallReturnsOwnerValue() throws Exception {
        CompletableFuture<String> result = coordinator.execute(
                "k1", () -> CompletableFuture.completedFuture("hello"));

        assertThat(result.get(1, TimeUnit.SECONDS)).isEqualTo("hello");
        assertThat(coordinator.getInflightState()).isEmpty(); // cleaned up
    }

    // ------------------------------------------------------------------
    // 2. Concurrent calls → coalesce (1 owner + N waiters)
    // ------------------------------------------------------------------
    @Test
    @DisplayName("concurrent calls coalesce — operation invoked once, all callers receive same value")
    void concurrentCallsCoalesce() throws Exception {
        AtomicInteger invocations = new AtomicInteger();
        CompletableFuture<String> ownerFuture = new CompletableFuture<>();

        ExecutorService executor = Executors.newFixedThreadPool(20);
        List<CompletableFuture<String>> results = new ArrayList<>();
        CountDownLatch ready = new CountDownLatch(20);

        try {
            for (int i = 0; i < 20; i++) {
                results.add(CompletableFuture.supplyAsync(() -> {
                    ready.countDown();
                    return coordinator.execute("k", () -> {
                        invocations.incrementAndGet();
                        return ownerFuture;
                    });
                }, executor).thenCompose(f -> f));
            }
            ready.await();
            // Give the coordinator a moment to register all 20 attaches.
            await().atMost(2, TimeUnit.SECONDS)
                    .until(() -> coordinator.getInflightState().size() == 1
                            && coordinator.getInflightState().get(0).waiterCount() == 20);

            ownerFuture.complete("shared-result");

            for (CompletableFuture<String> r : results) {
                assertThat(r.get(2, TimeUnit.SECONDS)).isEqualTo("shared-result");
            }
            assertThat(invocations.get()).isEqualTo(1);
        } finally {
            executor.shutdownNow();
        }

        // Cleanup verified after settlement.
        await().atMost(1, TimeUnit.SECONDS)
                .until(() -> coordinator.getInflightState().isEmpty());
    }

    // ------------------------------------------------------------------
    // 3. Owner failure → all waiters fail with same cause
    // ------------------------------------------------------------------
    @Test
    @DisplayName("owner failure propagates to all waiters with the same cause")
    void ownerFailurePropagates() throws Exception {
        CompletableFuture<String> ownerFuture = new CompletableFuture<>();
        RuntimeException cause = new RuntimeException("downstream-broke");

        CompletableFuture<String> r1 = coordinator.execute("k", () -> ownerFuture);
        CompletableFuture<String> r2 = coordinator.execute("k", () -> ownerFuture);
        CompletableFuture<String> r3 = coordinator.execute("k", () -> ownerFuture);

        ownerFuture.completeExceptionally(cause);

        for (CompletableFuture<String> r : List.of(r1, r2, r3)) {
            assertThatThrownBy(() -> r.get(1, TimeUnit.SECONDS))
                    .isInstanceOf(ExecutionException.class)
                    .hasCauseReference(cause);
        }
        await().atMost(1, TimeUnit.SECONDS)
                .until(() -> coordinator.getInflightState().isEmpty());
    }

    // ------------------------------------------------------------------
    // 4. Sequential calls (after owner completes) → new owner
    // ------------------------------------------------------------------
    @Test
    @DisplayName("sequential calls after owner completion start a new owner each time")
    void sequentialCallsRestartOwner() throws Exception {
        AtomicInteger invocations = new AtomicInteger();

        for (int i = 0; i < 5; i++) {
            String result = coordinator.execute("k", () -> {
                invocations.incrementAndGet();
                return CompletableFuture.completedFuture("v");
            }).get(1, TimeUnit.SECONDS);
            assertThat(result).isEqualTo("v");
        }

        assertThat(invocations.get()).isEqualTo(5);
    }

    // ------------------------------------------------------------------
    // 5. forceRelease → ForceReleasedException to all waiters, entry removed
    // ------------------------------------------------------------------
    @Test
    @DisplayName("forceRelease rejects all waiters with ForceReleasedException")
    void forceReleaseRejectsAllWaiters() throws Exception {
        CompletableFuture<String> hangingFuture = new CompletableFuture<>();

        CompletableFuture<String> r1 = coordinator.execute("k", () -> hangingFuture);
        CompletableFuture<String> r2 = coordinator.execute("k", () -> hangingFuture);

        await().atMost(1, TimeUnit.SECONDS)
                .until(() -> coordinator.getInflightState().size() == 1);

        coordinator.forceRelease("k", "operator-test").get(1, TimeUnit.SECONDS);

        for (CompletableFuture<String> r : List.of(r1, r2)) {
            assertThatThrownBy(() -> r.get(1, TimeUnit.SECONDS))
                    .isInstanceOf(ExecutionException.class)
                    .hasCauseInstanceOf(ForceReleasedException.class);
        }
        assertThat(coordinator.getInflightState()).isEmpty();
    }

    // ------------------------------------------------------------------
    // 6. Capacity rejection — atomic, no waiterCount mutation on reject
    // ------------------------------------------------------------------
    @Test
    @DisplayName("capacity exceeded → CongestionException; existing waiters unaffected; waiterCount NOT incremented")
    void capacityRejectionIsAtomicAndDoesNotMutateWaiterCount() throws Exception {
        CompletableFuture<String> hangingFuture = new CompletableFuture<>();
        SingleFlightOptions opts = SingleFlightOptions.builder().maxWaiters(3).build();

        // Owner + 2 waiters = 3 (cap)
        CompletableFuture<String> owner = coordinator.execute("k", () -> hangingFuture, opts);
        CompletableFuture<String> w1 = coordinator.execute("k", () -> hangingFuture, opts);
        CompletableFuture<String> w2 = coordinator.execute("k", () -> hangingFuture, opts);

        await().atMost(1, TimeUnit.SECONDS)
                .until(() -> {
                    var state = coordinator.getInflightState();
                    return state.size() == 1 && state.get(0).waiterCount() == 3;
                });

        // 4th attach must throw + waiterCount must STAY at 3
        assertThatThrownBy(() -> coordinator.execute("k", () -> hangingFuture, opts))
                .isInstanceOf(CongestionException.class)
                .extracting(t -> ((CongestionException) t).currentWaiters()).isEqualTo(3);

        assertThat(coordinator.getInflightState().get(0).waiterCount())
                .as("rejected attach must not mutate waiterCount")
                .isEqualTo(3);

        // Cleanup
        hangingFuture.complete("done");
        for (var f : List.of(owner, w1, w2)) {
            assertThat(f.get(1, TimeUnit.SECONDS)).isEqualTo("done");
        }
    }

    // ------------------------------------------------------------------
    // 7. Cross-key isolation — different keys do not interfere
    // ------------------------------------------------------------------
    @Test
    @DisplayName("different keys execute independently — no cross-talk")
    void crossKeyIsolation() throws Exception {
        CompletableFuture<String> ownerA = new CompletableFuture<>();
        CompletableFuture<String> ownerB = new CompletableFuture<>();

        CompletableFuture<String> a = coordinator.execute("A", () -> ownerA);
        CompletableFuture<String> b = coordinator.execute("B", () -> ownerB);

        assertThat(coordinator.getInflightState()).hasSize(2);

        ownerA.complete("a-value");
        assertThat(a.get(1, TimeUnit.SECONDS)).isEqualTo("a-value");
        assertThat(b).isNotDone();

        ownerB.complete("b-value");
        assertThat(b.get(1, TimeUnit.SECONDS)).isEqualTo("b-value");

        await().atMost(1, TimeUnit.SECONDS)
                .pollDelay(Duration.ofMillis(50))
                .until(() -> coordinator.getInflightState().isEmpty());
    }

    // ------------------------------------------------------------------
    // 8. Operation supplier throws synchronously → failed future, no leak
    // ------------------------------------------------------------------
    @Test
    @DisplayName("operation supplier throws synchronously → failed future, entry cleaned up")
    void operationThrowsSynchronously() {
        RuntimeException synchronousError = new RuntimeException("boom-sync");

        CompletableFuture<String> result = coordinator.execute("k", () -> {
            throw synchronousError;
        });

        assertThat(result).isCompletedExceptionally();
        assertThatThrownBy(result::get)
                .isInstanceOf(ExecutionException.class)
                .hasCauseReference(synchronousError);
        assertThat(coordinator.getInflightState()).isEmpty();
    }
}
