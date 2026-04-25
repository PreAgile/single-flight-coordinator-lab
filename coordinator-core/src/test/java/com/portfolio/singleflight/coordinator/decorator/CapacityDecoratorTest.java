package com.portfolio.singleflight.coordinator.decorator;

import com.portfolio.singleflight.coordinator.SingleFlightCoordinator;
import com.portfolio.singleflight.coordinator.SingleFlightOptions;
import com.portfolio.singleflight.coordinator.adapter.InProcessSingleFlightCoordinator;
import com.portfolio.singleflight.coordinator.exception.CongestionException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

class CapacityDecoratorTest {

    @Test
    @DisplayName("default cap is applied via options.orDefault — base atomically enforces")
    void defaultCapApplied() {
        InProcessSingleFlightCoordinator inner = new InProcessSingleFlightCoordinator();
        SingleFlightCoordinator coord = new CapacityDecorator(inner, 2);

        CompletableFuture<String> hanging = new CompletableFuture<>();
        coord.execute("k", () -> hanging); // owner
        coord.execute("k", () -> hanging); // waiter 1 (cap reached at 2)

        await().atMost(1, TimeUnit.SECONDS)
                .until(() -> inner.getInflightState().size() == 1
                        && inner.getInflightState().get(0).waiterCount() == 2);

        // 3rd attach exceeds cap
        assertThatThrownBy(() -> coord.execute("k", () -> hanging))
                .isInstanceOf(CongestionException.class);

        hanging.complete("done"); // cleanup
    }

    @Test
    @DisplayName("option cap overrides default")
    void optionCapOverridesDefault() {
        InProcessSingleFlightCoordinator inner = new InProcessSingleFlightCoordinator();
        // Default 100, but caller specifies 1
        SingleFlightCoordinator coord = new CapacityDecorator(inner, 100);
        SingleFlightOptions tightOpts = SingleFlightOptions.builder().maxWaiters(1).build();

        CompletableFuture<String> hanging = new CompletableFuture<>();
        coord.execute("k", () -> hanging, tightOpts); // owner (waiterCount=1, == cap)

        await().atMost(1, TimeUnit.SECONDS)
                .until(() -> inner.getInflightState().size() == 1);

        // 2nd attach exceeds cap=1
        assertThatThrownBy(() -> coord.execute("k", () -> hanging, tightOpts))
                .isInstanceOf(CongestionException.class);

        hanging.complete("done");
    }

    @Test
    @DisplayName("disabled cap (defaultMaxWaiters <= 0, no option) passes through")
    void disabledCapPassesThrough() {
        InProcessSingleFlightCoordinator inner = new InProcessSingleFlightCoordinator();
        SingleFlightCoordinator coord = new CapacityDecorator(inner, 0); // disabled

        CompletableFuture<String> hanging = new CompletableFuture<>();

        // Many attaches, all OK
        for (int i = 0; i < 100; i++) {
            coord.execute("k", () -> hanging);
        }

        await().atMost(1, TimeUnit.SECONDS)
                .until(() -> inner.getInflightState().size() == 1
                        && inner.getInflightState().get(0).waiterCount() == 100);

        hanging.complete("done");
    }

    @Test
    @DisplayName("rejected attach does NOT increment waiterCount — atomic guarantee")
    void rejectionDoesNotMutateWaiterCount() {
        InProcessSingleFlightCoordinator inner = new InProcessSingleFlightCoordinator();
        SingleFlightCoordinator coord = new CapacityDecorator(inner, 2);

        CompletableFuture<String> hanging = new CompletableFuture<>();
        coord.execute("k", () -> hanging);
        coord.execute("k", () -> hanging);

        await().atMost(1, TimeUnit.SECONDS)
                .until(() -> inner.getInflightState().size() == 1
                        && inner.getInflightState().get(0).waiterCount() == 2);

        // Multiple rejections — waiterCount stays at 2
        for (int i = 0; i < 5; i++) {
            assertThatThrownBy(() -> coord.execute("k", () -> hanging))
                    .isInstanceOf(CongestionException.class);
            assertThat(inner.getInflightState().get(0).waiterCount()).isEqualTo(2);
        }

        hanging.complete("done");
    }
}
