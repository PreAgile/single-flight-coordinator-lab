package com.portfolio.singleflight.coordinator.decorator;

import com.portfolio.singleflight.coordinator.SingleFlightCoordinator;
import com.portfolio.singleflight.coordinator.SingleFlightOptions;
import com.portfolio.singleflight.coordinator.adapter.InProcessSingleFlightCoordinator;
import com.portfolio.singleflight.coordinator.exception.DeadlineExceededException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DeadlineDecoratorTest {

    @Test
    @DisplayName("default deadline triggers DeadlineExceededException for never-completing operation")
    void defaultDeadlineTriggers() {
        SingleFlightCoordinator inner = new InProcessSingleFlightCoordinator();
        SingleFlightCoordinator coord = new DeadlineDecorator(inner, 50); // 50ms

        CompletableFuture<String> result = coord.execute("k", () -> new CompletableFuture<>());

        assertThatThrownBy(() -> result.get(2, TimeUnit.SECONDS))
                .isInstanceOf(ExecutionException.class)
                .hasCauseInstanceOf(DeadlineExceededException.class);
    }

    @Test
    @DisplayName("option deadline overrides default")
    void optionDeadlineOverridesDefault() {
        SingleFlightCoordinator inner = new InProcessSingleFlightCoordinator();
        // Default 5s, but option 50ms — option wins
        SingleFlightCoordinator coord = new DeadlineDecorator(inner, 5_000);
        SingleFlightOptions opts = SingleFlightOptions.builder().deadlineMs(50).build();

        CompletableFuture<String> result = coord.execute(
                "k", () -> new CompletableFuture<>(), opts);

        assertThatThrownBy(() -> result.get(2, TimeUnit.SECONDS))
                .isInstanceOf(ExecutionException.class)
                .hasCauseInstanceOf(DeadlineExceededException.class);
    }

    @Test
    @DisplayName("disabled deadline (0 default + no option) passes through")
    void disabledDeadlinePassesThrough() throws Exception {
        SingleFlightCoordinator inner = new InProcessSingleFlightCoordinator();
        SingleFlightCoordinator coord = new DeadlineDecorator(inner, 0); // disabled

        CompletableFuture<String> result = coord.execute(
                "k", () -> CompletableFuture.completedFuture("ok"));

        assertThat(result.get(1, TimeUnit.SECONDS)).isEqualTo("ok");
    }

    @Test
    @DisplayName("operation completes before deadline — normal value returned")
    void operationCompletesBeforeDeadline() throws Exception {
        SingleFlightCoordinator inner = new InProcessSingleFlightCoordinator();
        SingleFlightCoordinator coord = new DeadlineDecorator(inner, 1_000);

        CompletableFuture<String> result = coord.execute(
                "k", () -> CompletableFuture.completedFuture("fast"));

        assertThat(result.get(500, TimeUnit.MILLISECONDS)).isEqualTo("fast");
    }
}
