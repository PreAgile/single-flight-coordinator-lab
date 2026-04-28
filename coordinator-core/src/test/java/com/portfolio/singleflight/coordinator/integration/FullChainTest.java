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
import com.portfolio.singleflight.coordinator.exception.ForceReleasedException;
import com.portfolio.singleflight.coordinator.observability.MetricSink;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
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
 * 5-layer 데코레이터 양파 chain의 행동 계약(behavioral contract).
 *
 * <pre>
 * 호출자 → Telemetry → Heartbeat → Capacity → Deadline → InProcess (base)
 * </pre>
 *
 * <p>이 테스트 클래스는 chain을 한 덩어리로 봤을 때 어떤 약속을 지키는지를
 * <b>실행 가능한 명세서</b>로 기술한다. 각 {@code @Nested} 그룹은 chain이
 * 외부에 보장하는 한 가지 속성에 대응한다 — 위에서 아래로 읽으면 5개 layer가
 * 어떻게 결합돼 하나의 일관된 행동을 만드는지가 한 편의 이야기처럼 이어진다.
 *
 * <h2>변경자에게 — 이 테스트는 계약이다</h2>
 * <p>이 클래스(또는 의존 클래스 / 데코레이터 / 어댑터)를 수정하는 사람은 모든
 * 테스트가 초록색인 상태로 PR을 올려야 한다. 하나라도 빨간색이면 5-layer chain이
 * 사용자에게 한 약속 — "어떤 실패도 Telemetry가 정확히 분류한다", "정책 게이트는
 * 양파 안쪽까지 도달하지 않는다", "관측성은 critical path를 흔들지 않는다" —
 * 중 하나가 깨진 것이다. 의도된 계약 변경이라면 이 테스트도 함께 갱신하고
 * PR 설명에 변경 사유를 남긴다.
 *
 * <h2>이 chain이 보장하는 8가지 속성</h2>
 * <ol>
 *   <li><b>정상 흐름:</b> chain을 통과한 정상 결과는 변형 없이 호출자에게
 *       도달하고, Telemetry는 {@code status=success}로 분류한다.</li>
 *   <li><b>단일 layer 에러 분류:</b> InProcess(core) 일반 실패는
 *       {@code status=failure}, Deadline 만료는 {@code status=deadline},
 *       Capacity 거부는 {@code status=congestion}으로 outermost Telemetry가
 *       <b>정확히</b> 분류한다 — 양파 안쪽에서 발생한 모든 새 예외 타입을
 *       시야 안에 두기 위해 Telemetry가 outermost에 위치한다는 결정의 회귀
 *       가드.</li>
 *   <li><b>다중 layer race:</b> 두 layer의 실패 신호가 동시에 발생할 수 있는
 *       시나리오에서, 결정적 우선순위가 있는 경우(Capacity sync throw는 Deadline
 *       도달 자체를 차단)는 그 우선순위를 박아넣고, 본질적으로 race인 경우
 *       (Deadline 만료 vs core failure)는 "둘 중 하나의 status만 기록"이라는
 *       안전한 단언으로 race window를 lock-in한다.</li>
 *   <li><b>정책 게이트 short-circuit:</b> Capacity가 cap을 사유로 거부하면
 *       Deadline/InProcess까지 흐름이 도달하지 않으며, 호출자 측 supplier도
 *       호출되지 않고 inflight 카운트도 변하지 않는다 — 양파 바깥에서 끊어내는
 *       구조의 핵심 보장.</li>
 *   <li><b>관측성 격리:</b> Heartbeat은 critical path에 끼어들지 않는다 —
 *       별도 timer 스레드의 inflight gauge / long_running counter emit이
 *       chain 결과에 어떤 영향도 주지 못하며, scan이 throw해도 chain은
 *       동작을 유지한다.</li>
 *   <li><b>운영 명령 전파:</b> {@code forceRelease}는 chain의 모든 layer를
 *       관통해 base까지 도달하고 매달린 호출자에게
 *       {@link ForceReleasedException}이 전파되며, {@code getInflightState}는
 *       base 스냅샷과 동일한 결과를 chain 바깥에서도 노출한다.</li>
 *   <li><b>코알레싱 + chain 통합:</b> 같은 키 동시 호출 N건이 5-layer를 모두
 *       통과해도 supplier는 단 1번만 실행되고 N명 모두 같은 결과를 받는다 —
 *       양파가 두꺼워져도 single-flight 본질이 깨지지 않는다는 보장.</li>
 *   <li><b>입력 계약:</b> chain 진입점에 null 인자가 들어오면 가장 바깥
 *       Telemetry가 즉시 NPE로 거부한다 (fail-fast).</li>
 * </ol>
 *
 * <h2>그룹별 단일 관심사 원칙</h2>
 * <p>각 {@code @Nested} 그룹의 테스트는 자기 그룹의 보장 속성 <b>하나</b>에만
 * 집중한다 — 예: [3] 다중 layer race 테스트가 우연히 inflight cleanup에 의존한다고
 * 해서 거기에 cleanup 단언을 끼워 넣지 않는다. cleanup은 base 어댑터의 책임이며
 * {@code InProcessSingleFlightCoordinatorTest}의 [3] 자원 정리 그룹에서 단독
 * 검증된다. 본 테스트는 매달린 future를 끝맺을 때 {@code hangingFuture.complete}
 * 만 호출하고 후속 cleanup은 단언하지 않는다 — 테스트가 깨지면 어느 보장이
 * 깨졌는지 그룹명에 즉시 보이게 하기 위함이다.
 *
 * <p>예외: 비동기 race가 본질적인 시나리오([3] 다중 layer race, [7] 코알레싱)는
 * 시나리오의 끝을 안전하게 기다리기 위해 {@link #awaitSingleInflightWithWaiterCount}
 * 같은 동기화 헬퍼를 사용한다. 이건 cleanup 회귀 탐지가 아니라 <b>비동기
 * 시나리오 종료 동기화</b>이며, 다른 테스트의 cleanup 단언과 역할이 다르다.
 */
@DisplayName("Full Decorator Chain — Telemetry → Heartbeat → Capacity → Deadline → InProcess 양파 통합")
class FullChainTest {

    private static final String KEY = "k";
    private static final String DURATION_METRIC = "singleflight.owner_duration_ms";

    /** 정책/타이밍이 본문 의도를 흐리지 않도록 캡 5, 데드라인 200ms로 고정한 표준 fixture. */
    private static final int CAP = 5;
    private static final long DEFAULT_DEADLINE_MS = 200;

    /** [3]/[4]에서 deadline이 본질이 아닐 때 사용하는 충분히 긴 deadline override. */
    private static final SingleFlightOptions LONG_DEADLINE =
            SingleFlightOptions.builder().deadlineMs(5_000).build();

    private InProcessSingleFlightCoordinator base;
    private HeartbeatDecorator heartbeat;
    private RecordingMetricSink metrics;
    private SingleFlightCoordinator chain;

    @BeforeEach
    void setUp() {
        metrics = new RecordingMetricSink();
        chain = buildFullChain();
    }

    @AfterEach
    void tearDown() {
        if (heartbeat != null) {
            heartbeat.close();
        }
    }

    // ====================================================================
    // [1] 정상 흐름 — chain 통과 시 결과 변형 없음 + status=success
    // ====================================================================
    @Nested
    @DisplayName("[1] 정상 흐름 — chain을 통과한 정상 결과는 변형 없이 호출자에게 도달하고 Telemetry는 status=success로 분류한다")
    class HappyPath {

        @Test
        @DisplayName("정상 완료된 inner future의 값은 5-layer를 거쳐도 변형 없이 그대로 호출자에게 전달된다")
        void successValueFlowsThroughChainUnchanged() throws Exception {
            String result = chain.execute(KEY, () -> CompletableFuture.completedFuture("v"))
                    .get(1, TimeUnit.SECONDS);

            assertThat(result).isEqualTo("v");
        }

        @Test
        @DisplayName("정상 완료 시 outermost Telemetry는 status=success 태그로 owner_duration_ms histogram을 emit한다")
        void successPathEmitsStatusSuccess() throws Exception {
            chain.execute(KEY, () -> CompletableFuture.completedFuture("v"))
                    .get(1, TimeUnit.SECONDS);

            assertThat(metrics.histogramTagsLast(DURATION_METRIC))
                    .containsEntry("status", "success");
            assertThat(metrics.histogramValueLast(DURATION_METRIC)).isNotNegative();
        }
    }

    // ====================================================================
    // [2] 단일 layer 에러 분류 — outermost Telemetry의 분류 정확성
    // ====================================================================
    @Nested
    @DisplayName("[2] 단일 layer 에러 분류 — InProcess/Deadline/Capacity 각 layer의 실패 신호를 outermost Telemetry가 정확히 status로 분류한다")
    class SingleLayerErrorClassification {

        @Test
        @DisplayName("InProcess 일반 실패(future failure)는 status=failure로 분류되고 cause는 변형 없이 전파된다")
        void innerFutureFailureClassifiedAsFailure() {
            RuntimeException cause = new RuntimeException("downstream-broke");

            CompletableFuture<String> result = chain.execute(
                    KEY, () -> CompletableFuture.failedFuture(cause));

            assertThatThrownBy(() -> result.get(1, TimeUnit.SECONDS))
                    .isInstanceOf(ExecutionException.class)
                    .hasCauseReference(cause);
            assertThat(metrics.histogramTagsLast(DURATION_METRIC))
                    .containsEntry("status", "failure");
        }

        @Test
        @DisplayName("supplier가 동기로 throw해도 chain 끝까지 전파되지 않고 future failure로 변환되어 status=failure로 분류된다")
        void supplierSyncThrowClassifiedAsFailure() {
            // DeadlineDecorator의 wrapped supplier가 sync throw를 catch해
            // failedFuture로 변환하고, base 어댑터도 catch 후 failedFuture로
            // 변환한다 — 어느 layer가 잡든 결과적으로 sync throw는 chain 바깥
            // 호출자에게 도달하지 않는다는 사실을 박아둔다.
            RuntimeException syncError = new RuntimeException("boom-sync");

            CompletableFuture<String> result = chain.execute(KEY, () -> {
                throw syncError;
            });

            assertThatThrownBy(() -> result.get(1, TimeUnit.SECONDS))
                    .isInstanceOf(ExecutionException.class)
                    .hasCauseReference(syncError);
            assertThat(metrics.histogramTagsLast(DURATION_METRIC))
                    .containsEntry("status", "failure");
        }

        @Test
        @DisplayName("supplier가 null future를 반환하면 IllegalStateException 실패가 되어 status=failure로 분류된다")
        void supplierNullFutureClassifiedAsFailure() {
            CompletableFuture<String> result = chain.execute(KEY, () -> null);

            assertThatThrownBy(() -> result.get(1, TimeUnit.SECONDS))
                    .isInstanceOf(ExecutionException.class)
                    .hasCauseInstanceOf(IllegalStateException.class)
                    .hasRootCauseMessage("operation supplier returned null");
            assertThat(metrics.histogramTagsLast(DURATION_METRIC))
                    .containsEntry("status", "failure");
        }

        @Test
        @DisplayName("Deadline 만료는 status=deadline으로 분류되고 모든 호출자에게 DeadlineExceededException이 전파된다")
        void deadlineExceededClassifiedAsDeadline() {
            CompletableFuture<String> hanging = new CompletableFuture<>();

            CompletableFuture<String> r1 = chain.execute(KEY, () -> hanging);
            CompletableFuture<String> r2 = chain.execute(KEY, () -> hanging);

            for (CompletableFuture<String> r : List.of(r1, r2)) {
                assertThatThrownBy(() -> r.get(2, TimeUnit.SECONDS))
                        .isInstanceOf(ExecutionException.class)
                        .hasCauseInstanceOf(DeadlineExceededException.class);
            }
            assertThat(metrics.histogramTagsLast(DURATION_METRIC))
                    .containsEntry("status", "deadline");
        }

        @Test
        @DisplayName("Capacity sync throw(CongestionException)는 try/catch에 잡혀 status=congestion으로 기록된 뒤 호출자에게 그대로 재전파된다")
        void capacityRejectionClassifiedAsCongestion() {
            // CapacityDecorator → InProcess의 atomic compute에서 sync throw하는
            // CongestionException은 future-failure 채널이 아니라 호출자 스레드를
            // 통해 위로 올라온다. Telemetry가 try/catch로 잡지 않으면
            // status 분류가 통째로 누락되는 회귀를 차단.
            CompletableFuture<String> hanging = new CompletableFuture<>();

            // owner + 4 waiter = cap(5) 도달
            for (int i = 0; i < CAP; i++) {
                chain.execute(KEY, () -> hanging, LONG_DEADLINE);
            }
            awaitSingleInflightWithWaiterCount(CAP);

            assertThatThrownBy(() -> chain.execute(KEY, () -> hanging, LONG_DEADLINE))
                    .isInstanceOf(CongestionException.class);

            assertThat(metrics.histogramTagsLast(DURATION_METRIC))
                    .containsEntry("status", "congestion");

            hanging.complete("done");
        }

        @Test
        @DisplayName("forceRelease로 매달린 작업이 끊기면 매달린 호출자는 ForceReleasedException을 받고 Telemetry는 status=force_released로 분류한다")
        void forceReleaseClassifiedAsForceReleased() throws Exception {
            CompletableFuture<String> hanging = new CompletableFuture<>();

            CompletableFuture<String> r = chain.execute(KEY, () -> hanging, LONG_DEADLINE);
            awaitSingleInflightWithWaiterCount(1);

            chain.forceRelease(KEY, "operator-test").get(1, TimeUnit.SECONDS);

            assertThatThrownBy(() -> r.get(1, TimeUnit.SECONDS))
                    .isInstanceOf(ExecutionException.class)
                    .hasCauseInstanceOf(ForceReleasedException.class);
            assertThat(metrics.histogramTagsLast(DURATION_METRIC))
                    .containsEntry("status", "force_released");
        }
    }

    // ====================================================================
    // [3] 다중 layer race — 결정적 우선순위 + 본질적 race의 안전한 단언
    // ====================================================================
    @Nested
    @DisplayName("[3] 다중 layer race — 결정적 우선순위 시나리오는 그 우선순위를 박고, 본질적 race는 \"둘 중 하나의 status만 기록\"으로 lock-in한다")
    class MultiLayerRace {

        @Test
        @DisplayName("Capacity가 sync throw하면 Deadline/InProcess는 도달하지 않으므로 결과는 결정적으로 status=congestion이다 (race가 아닌 결정적 우선순위)")
        void capacityShortCircuitsBeforeDeadlineDecisively() {
            // Capacity가 양파 더 바깥(2번째 layer 안쪽)이라 cap이 차면
            // Deadline까지 흐름이 도달하지 못한다. 같은 시각에 Deadline 만료
            // "조건"이 충족되더라도 실제 신호는 발생할 기회가 없다 — 결정적이다.
            CompletableFuture<String> hanging = new CompletableFuture<>();

            for (int i = 0; i < CAP; i++) {
                chain.execute(KEY, () -> hanging, LONG_DEADLINE);
            }
            awaitSingleInflightWithWaiterCount(CAP);

            assertThatThrownBy(() -> chain.execute(KEY, () -> hanging, LONG_DEADLINE))
                    .isInstanceOf(CongestionException.class);

            // race가 아니라는 사실을 status 분류로 박아둠
            assertThat(metrics.histogramTagsLast(DURATION_METRIC))
                    .containsEntry("status", "congestion");

            hanging.complete("done");
        }

        @Test
        @DisplayName("Deadline 만료 vs InProcess core failure가 거의 동시일 때는 본질적 race이므로 둘 중 하나의 status만 기록되어야 한다")
        void deadlineVsCoreFailureRaceProducesOneOfTwoStatuses() {
            // 본질적으로 race인 시나리오 — orTimeout이 trigger되는 시점과
            // user supplier의 future가 실패로 settle되는 시점이 매우 가까울 때
            // 어느 신호가 먼저 future를 settle하는가가 결과를 결정한다.
            // 어느 쪽이든 outcome은 실패이고 status는 deadline 또는 failure
            // 중 하나여야 한다 — 이 두 가지 외의 status는 분류 회귀.
            CompletableFuture<String> almostFails = new CompletableFuture<>();
            // deadline=200ms와 거의 동시(150~250ms)에 user 측 실패가 일어나도록
            // 별도 스케줄러로 trigger.
            new Thread(() -> {
                try {
                    Thread.sleep(190);
                } catch (InterruptedException ignored) {
                }
                almostFails.completeExceptionally(new RuntimeException("user-side-broke"));
            }, "race-injector").start();

            CompletableFuture<String> result = chain.execute(KEY, () -> almostFails);

            assertThatThrownBy(() -> result.get(2, TimeUnit.SECONDS))
                    .isInstanceOf(ExecutionException.class);

            await().atMost(2, TimeUnit.SECONDS).untilAsserted(() ->
                    assertThat(metrics.histogramTagsLast(DURATION_METRIC).get("status"))
                            .as("race winner는 둘 중 하나여야 한다")
                            .isIn("deadline", "failure"));
        }

        @Test
        @DisplayName("Deadline 만료 vs forceRelease가 거의 동시여도 결과는 어쨌든 실패이며 status는 deadline 또는 force_released 중 하나로 결정된다")
        void deadlineVsForceReleaseRaceProducesOneOfTwoStatuses() {
            // forceRelease는 base에서 future를 completeExceptionally로 settle하는데,
            // 이 future는 DeadlineDecorator가 wrapping한 inner의 underlying future다.
            // 한쪽이 먼저 settle하면 다른 쪽은 no-op이라 race outcome은 둘 중 하나.
            // deadline 200ms와 forceRelease를 거의 동시에 trigger해 race 유발.
            CompletableFuture<String> hanging = new CompletableFuture<>();
            long deadlineMs = 200;
            CompletableFuture<String> r = chain.execute(
                    KEY, () -> hanging,
                    SingleFlightOptions.builder().deadlineMs(deadlineMs).build());
            awaitSingleInflightWithWaiterCount(1);

            new Thread(() -> {
                try {
                    // deadline 시점에 거의 정확히 도달하도록 — race window를 의도적으로 좁힘
                    Thread.sleep(deadlineMs - 10);
                } catch (InterruptedException ignored) {
                }
                chain.forceRelease(KEY, "race-test");
            }, "race-injector").start();

            assertThatThrownBy(() -> r.get(2, TimeUnit.SECONDS))
                    .isInstanceOf(ExecutionException.class);

            await().atMost(2, TimeUnit.SECONDS).untilAsserted(() ->
                    assertThat(metrics.histogramTagsLast(DURATION_METRIC).get("status"))
                            .as("race winner는 둘 중 하나여야 한다")
                            .isIn("deadline", "force_released"));
        }

        @Test
        @DisplayName("거부된 호출과 정상 owner는 서로 다른 status로 별개 기록된다 — congestion(거부) + success(정상 완료) 둘 다 emit")
        void rejectedAndOwnerAreRecordedSeparately() {
            CompletableFuture<String> hanging = new CompletableFuture<>();

            for (int i = 0; i < CAP; i++) {
                chain.execute(KEY, () -> hanging, LONG_DEADLINE);
            }
            awaitSingleInflightWithWaiterCount(CAP);

            assertThatThrownBy(() -> chain.execute(KEY, () -> hanging, LONG_DEADLINE))
                    .isInstanceOf(CongestionException.class);

            // 거부 시점에 status=congestion 1건이 이미 emit돼 있다
            assertThat(metrics.histogramTagsAll(DURATION_METRIC))
                    .anyMatch(tags -> "congestion".equals(tags.get("status")));

            // owner를 정상 완료 → success도 별개로 emit
            hanging.complete("done");

            await().atMost(1, TimeUnit.SECONDS).untilAsserted(() ->
                    assertThat(metrics.histogramTagsAll(DURATION_METRIC))
                            .anyMatch(tags -> "success".equals(tags.get("status"))));
        }
    }

    // ====================================================================
    // [4] 정책 게이트 short-circuit
    // ====================================================================
    @Nested
    @DisplayName("[4] 정책 게이트 short-circuit — Capacity 거부는 양파 안쪽까지 흐름이 닿기 전에 끊어내고 inflight 카운트에도 영향이 없다")
    class PolicyGateShortCircuit {

        @Test
        @DisplayName("Capacity 거부는 supplier를 호출하지 않는다 — 거부된 호출자의 supplier invocation count는 0이다")
        void rejectedCallDoesNotInvokeSupplier() {
            CompletableFuture<String> hanging = new CompletableFuture<>();
            AtomicInteger ownerInvocations = new AtomicInteger();
            AtomicInteger rejectedInvocations = new AtomicInteger();

            // owner + 4 waiter — cap 도달
            chain.execute(KEY, () -> {
                ownerInvocations.incrementAndGet();
                return hanging;
            }, LONG_DEADLINE);
            for (int i = 0; i < CAP - 1; i++) {
                chain.execute(KEY, () -> hanging, LONG_DEADLINE);
            }
            awaitSingleInflightWithWaiterCount(CAP);

            assertThatThrownBy(() -> chain.execute(KEY, () -> {
                rejectedInvocations.incrementAndGet();
                return CompletableFuture.completedFuture("nope");
            }, LONG_DEADLINE)).isInstanceOf(CongestionException.class);

            assertThat(ownerInvocations.get()).as("owner supplier는 1번만 실행되어야 한다").isEqualTo(1);
            assertThat(rejectedInvocations.get()).as("거부된 supplier는 호출되지 않아야 한다").isZero();

            hanging.complete("done");
        }

        @Test
        @DisplayName("Capacity 거부는 base의 waiterCount를 변경하지 않는다 — 거부 전후 카운트가 동일")
        void rejectedCallDoesNotMutateWaiterCount() {
            CompletableFuture<String> hanging = new CompletableFuture<>();

            for (int i = 0; i < CAP; i++) {
                chain.execute(KEY, () -> hanging, LONG_DEADLINE);
            }
            awaitSingleInflightWithWaiterCount(CAP);

            int countBefore = base.getInflightState().get(0).waiterCount();
            assertThatThrownBy(() -> chain.execute(KEY, () -> hanging, LONG_DEADLINE))
                    .isInstanceOf(CongestionException.class);
            int countAfter = base.getInflightState().get(0).waiterCount();

            assertThat(countAfter).isEqualTo(countBefore).isEqualTo(CAP);

            hanging.complete("done");
        }

        @Test
        @DisplayName("Deadline 만료가 결정한 status는 그 후 inner future가 늦게 settle해도 덮어쓰지 않는다 — 첫 신호가 결과를 확정한다")
        void firstSignalWinsAndIsNotOverwrittenByLateSettlement() throws Exception {
            // CompletableFuture.complete*는 한 번만 효과가 있고 그 후 호출은 no-op.
            // Telemetry의 whenComplete는 settle 시점의 결과 1건만 본다.
            // 이 테스트는 deadline trigger 이후 underlying future가 늦게 success로
            // 와도 status가 success로 덮어써지지 않음을 박아둔다.
            CompletableFuture<String> lateSuccess = new CompletableFuture<>();

            CompletableFuture<String> r = chain.execute(KEY, () -> lateSuccess);

            assertThatThrownBy(() -> r.get(2, TimeUnit.SECONDS))
                    .isInstanceOf(ExecutionException.class)
                    .hasCauseInstanceOf(DeadlineExceededException.class);

            // deadline이 settle 후 늦게 underlying이 성공으로 와도...
            lateSuccess.complete("late-but-too-late");

            // 잠시 기다려 추가 metric emit이 없는지 확인
            Thread.sleep(150);

            assertThat(metrics.histogramTagsLast(DURATION_METRIC))
                    .as("늦은 success는 status를 덮어쓰지 못한다")
                    .containsEntry("status", "deadline");
            assertThat(metrics.histogramCalls(DURATION_METRIC))
                    .as("settle은 1번 → histogram도 1건만 emit")
                    .isEqualTo(1);
        }
    }

    // ====================================================================
    // [5] 관측성 격리 — Heartbeat은 critical path에 무영향
    // ====================================================================
    @Nested
    @DisplayName("[5] 관측성 격리 — Heartbeat의 별도 채널 metric은 critical path 결과에 어떤 영향도 주지 못한다")
    class HeartbeatIsolation {

        @Test
        @DisplayName("짧은 scanInterval이 자동 tick을 일으켜 inflight gauge가 emit되어도 chain의 정상 결과는 영향받지 않는다")
        void heartbeatTicksDoNotAffectChainResult() throws Exception {
            // 기본 fixture는 10분 scanInterval — 격리 검증을 위해 짧은 tick chain을 새로 빌드.
            tearDown();
            metrics = new RecordingMetricSink();
            chain = buildChainWithHeartbeat(Duration.ofMillis(20), Duration.ofMinutes(10));

            CompletableFuture<String> hanging = new CompletableFuture<>();
            CompletableFuture<String> r = chain.execute(KEY, () -> hanging, LONG_DEADLINE);

            awaitGaugeCallsAtLeast(2);
            hanging.complete("v");

            assertThat(r.get(1, TimeUnit.SECONDS)).isEqualTo("v");
            assertThat(metrics.histogramTagsLast(DURATION_METRIC))
                    .containsEntry("status", "success");
        }

        @Test
        @DisplayName("Heartbeat scan이 throw해도 chain 결과는 영향받지 않는다 — 관측성은 critical path를 흔들 수 없다")
        void heartbeatScanThrowingDoesNotAffectChain() throws Exception {
            // ThrowOnceCoordinator를 base와 capacity 사이에 끼워 scan에서만 throw하도록.
            tearDown();
            metrics = new RecordingMetricSink();
            base = new InProcessSingleFlightCoordinator();
            ThrowOnceCoordinator flaky = new ThrowOnceCoordinator(base);
            SingleFlightCoordinator deadline = new DeadlineDecorator(flaky, DEFAULT_DEADLINE_MS);
            SingleFlightCoordinator capacity = new CapacityDecorator(deadline, CAP);
            heartbeat = new HeartbeatDecorator(
                    capacity, Duration.ofMillis(20), Duration.ZERO, metrics);
            chain = new TelemetryDecorator(heartbeat, metrics);

            // scan이 첫 번째 호출에서 throw할 때 chain의 정상 흐름은 멀쩡한지 확인
            String v = chain.execute(KEY, () -> CompletableFuture.completedFuture("v"))
                    .get(1, TimeUnit.SECONDS);

            assertThat(v).isEqualTo("v");
            assertThat(metrics.histogramTagsLast(DURATION_METRIC))
                    .containsEntry("status", "success");
        }
    }

    // ====================================================================
    // [6] 운영 명령 전파
    // ====================================================================
    @Nested
    @DisplayName("[6] 운영 명령 전파 — getInflightState/forceRelease는 5-layer를 모두 관통해 base까지 도달한다")
    class OperationalCommandPropagation {

        @Test
        @DisplayName("chain.getInflightState()는 base의 스냅샷과 동일한 결과를 반환한다 — layer 추가가 진단 채널을 가리지 않는다")
        void getInflightStateReturnsBaseSnapshot() {
            CompletableFuture<String> hanging = new CompletableFuture<>();
            chain.execute(KEY, () -> hanging, LONG_DEADLINE);

            awaitSingleInflightWithWaiterCount(1);

            assertThat(chain.getInflightState()).isEqualTo(base.getInflightState());

            hanging.complete("done");
        }

        @Test
        @DisplayName("chain.forceRelease(key)는 base까지 도달해 매달린 호출자에게 ForceReleasedException을 전파한다")
        void forceReleaseReachesBase() throws Exception {
            CompletableFuture<String> hanging = new CompletableFuture<>();
            CompletableFuture<String> r = chain.execute(KEY, () -> hanging, LONG_DEADLINE);

            awaitSingleInflightWithWaiterCount(1);

            chain.forceRelease(KEY, "operator-test").get(1, TimeUnit.SECONDS);

            assertThatThrownBy(() -> r.get(1, TimeUnit.SECONDS))
                    .isInstanceOf(ExecutionException.class)
                    .hasCauseInstanceOf(ForceReleasedException.class);
        }
    }

    // ====================================================================
    // [7] 코알레싱 + chain 통합
    // ====================================================================
    @Nested
    @DisplayName("[7] 코알레싱 + chain 통합 — 양파가 두꺼워져도 같은 키 동시 호출자는 1번의 실행을 공유한다")
    class CoalescingThroughChain {

        @Test
        @DisplayName("동시 20건이 chain을 통과해도 supplier는 단 1번 실행되고 20명 모두 같은 결과를 받는다")
        void concurrentCallsCoalesceThroughFullChain() throws Exception {
            // 본 그룹의 관심사는 코알레싱 — capacity cap이 시야를 가리지 않도록
            // 충분히 큰 maxWaiters와 긴 deadline으로 옵션 override.
            SingleFlightOptions roomy = SingleFlightOptions.builder()
                    .deadlineMs(5_000)
                    .maxWaiters(100)
                    .build();
            AtomicInteger invocations = new AtomicInteger();
            CompletableFuture<String> ownerFuture = new CompletableFuture<>();

            ExecutorService executor = Executors.newFixedThreadPool(20);
            List<CompletableFuture<String>> results = new ArrayList<>();
            CountDownLatch ready = new CountDownLatch(20);

            try {
                for (int i = 0; i < 20; i++) {
                    results.add(CompletableFuture.supplyAsync(() -> {
                        ready.countDown();
                        return chain.execute(KEY, () -> {
                            invocations.incrementAndGet();
                            return ownerFuture;
                        }, roomy);
                    }, executor).thenCompose(f -> f));
                }
                ready.await();
                awaitSingleInflightWithWaiterCount(20);

                ownerFuture.complete("shared");

                for (CompletableFuture<String> r : results) {
                    assertThat(r.get(2, TimeUnit.SECONDS)).isEqualTo("shared");
                }
                assertThat(invocations.get())
                        .as("supplier는 chain을 거쳐도 단 1번만 실행되어야 한다")
                        .isEqualTo(1);
            } finally {
                executor.shutdownNow();
            }
        }

        @Test
        @DisplayName("서로 다른 키는 chain을 통과해도 독립적으로 실행되며 간섭하지 않는다")
        void crossKeyIsolationHoldsAcrossChain() throws Exception {
            CompletableFuture<String> ownerA = new CompletableFuture<>();
            CompletableFuture<String> ownerB = new CompletableFuture<>();

            CompletableFuture<String> a = chain.execute("A", () -> ownerA, LONG_DEADLINE);
            CompletableFuture<String> b = chain.execute("B", () -> ownerB, LONG_DEADLINE);

            assertThat(base.getInflightState()).hasSize(2);

            ownerA.complete("a");
            ownerB.complete("b");

            assertThat(a.get(1, TimeUnit.SECONDS)).isEqualTo("a");
            assertThat(b.get(1, TimeUnit.SECONDS)).isEqualTo("b");
        }
    }

    // ====================================================================
    // [8] 입력 계약 — chain 진입점 fail-fast
    // ====================================================================
    @Nested
    @DisplayName("[8] 입력 계약 — chain 진입점에 null 인자가 들어오면 가장 바깥 layer에서 즉시 NPE로 거부된다 (fail-fast)")
    class InputContract {

        @Test
        @DisplayName("chain.execute(\"k\", op, null) → NPE(\"options\") — Capacity layer가 가장 먼저 options를 검증한다")
        void chainRejectsNullOptions() {
            // Telemetry는 options.telemetryTag()를 즉시 호출하므로 options가 null이면
            // NPE가 그 라인에서 터진다(JVM helpful-NPE는 invokeinterface NullPointerException).
            // CapacityDecorator도 명시적 requireNonNull("options")를 한다.
            // 어느 쪽이든 NPE이므로 인스턴스 검사로 단언.
            assertThatThrownBy(() -> chain.execute(
                    KEY, () -> CompletableFuture.completedFuture("v"), null))
                    .isInstanceOf(NullPointerException.class);
        }

        @Test
        @DisplayName("chain.forceRelease(null, reason) → NPE(\"key\") — base 어댑터가 진입 즉시 거부한다")
        void chainForceReleaseRejectsNullKey() {
            assertThatThrownBy(() -> chain.forceRelease(null, "reason"))
                    .isInstanceOf(NullPointerException.class)
                    .hasMessage("key");
        }
    }

    // ====================================================================
    // Helpers — chain builder, 비동기 동기화, recording metric sink
    // ====================================================================

    /** 표준 fixture chain — Telemetry → Heartbeat(quiet) → Capacity(5) → Deadline(200ms) → InProcess. */
    private SingleFlightCoordinator buildFullChain() {
        return buildChainWithHeartbeat(Duration.ofMinutes(10), Duration.ofMinutes(10));
    }

    /**
     * Heartbeat scanInterval/threshold만 바꿔 chain을 재구성한다.
     * 기본 fixture는 자동 tick이 사실상 없는 quiet heartbeat을 쓰지만,
     * [5] 관측성 격리 그룹은 짧은 tick으로 자동 발화 시나리오를 검증해야 한다.
     */
    private SingleFlightCoordinator buildChainWithHeartbeat(
            Duration scanInterval, Duration longRunningThreshold) {
        base = new InProcessSingleFlightCoordinator();
        SingleFlightCoordinator deadline = new DeadlineDecorator(base, DEFAULT_DEADLINE_MS);
        SingleFlightCoordinator capacity = new CapacityDecorator(deadline, CAP);
        heartbeat = new HeartbeatDecorator(capacity, scanInterval, longRunningThreshold, metrics);
        return new TelemetryDecorator(heartbeat, metrics);
    }

    private void awaitSingleInflightWithWaiterCount(int waiterCount) {
        await().atMost(2, TimeUnit.SECONDS)
                .until(() -> {
                    var state = base.getInflightState();
                    return state.size() == 1 && state.get(0).waiterCount() == waiterCount;
                });
    }

    private void awaitGaugeCallsAtLeast(long minCalls) {
        await().atMost(1, TimeUnit.SECONDS)
                .untilAsserted(() -> assertThat(
                        metrics.gaugeCalls("singleflight.inflight_count"))
                        .isGreaterThanOrEqualTo(minCalls));
    }

    /**
     * 이름 + 태그 + 값 모두 정직하게 분리해 기록하는 sink. counter / gauge / histogram을
     * 한 곳에 모아 chain의 다중 status emit을 검증할 수 있도록 last-wins 헬퍼와
     * 키-필터 헬퍼({@link #histogramTagsLastFor})를 함께 제공한다 — race 시나리오에서
     * 같은 metric이 여러 번 emit되어도 키별로 분리된 분류 결과를 단언할 수 있어야
     * 하기 때문.
     */
    private static final class RecordingMetricSink implements MetricSink {
        private final List<String> counterNames = new ArrayList<>();
        private final List<RecordedSample> gauges = new ArrayList<>();
        private final List<RecordedSample> histograms = new ArrayList<>();

        @Override
        public synchronized void incrementCounter(String name, Map<String, String> tags) {
            counterNames.add(name);
        }

        @Override
        public synchronized void recordHistogram(String name, double value, Map<String, String> tags) {
            histograms.add(new RecordedSample(name, value, Map.copyOf(tags)));
        }

        @Override
        public synchronized void recordGauge(String name, double value, Map<String, String> tags) {
            gauges.add(new RecordedSample(name, value, Map.copyOf(tags)));
        }

        synchronized long histogramCalls(String name) {
            return histograms.stream().filter(s -> s.name.equals(name)).count();
        }

        synchronized long gaugeCalls(String name) {
            return gauges.stream().filter(s -> s.name.equals(name)).count();
        }

        synchronized long counterCalls(String name) {
            return counterNames.stream().filter(name::equals).count();
        }

        synchronized Map<String, String> histogramTagsLast(String name) {
            return histograms.stream()
                    .filter(s -> s.name.equals(name))
                    .reduce((a, b) -> b)
                    .map(RecordedSample::tags)
                    .orElseThrow(() -> new AssertionError(
                            "no histogram sample recorded for " + name));
        }

        synchronized double histogramValueLast(String name) {
            return histograms.stream()
                    .filter(s -> s.name.equals(name))
                    .reduce((a, b) -> b)
                    .map(RecordedSample::value)
                    .orElseThrow(() -> new AssertionError(
                            "no histogram sample recorded for " + name));
        }

        synchronized List<Map<String, String>> histogramTagsAll(String name) {
            return histograms.stream()
                    .filter(s -> s.name.equals(name))
                    .map(RecordedSample::tags)
                    .toList();
        }

        private record RecordedSample(String name, double value, Map<String, String> tags) {}
    }

    /**
     * [5] 관측성 격리 전용 test double — 첫 {@code getInflightState()} 호출에서만
     * throw해서 "scan 중 inner 실패 → chain critical path는 영향 없음" 시나리오를
     * deterministic하게 재현한다.
     */
    private static final class ThrowOnceCoordinator implements SingleFlightCoordinator {
        private final SingleFlightCoordinator delegate;
        private boolean firstCall = true;

        ThrowOnceCoordinator(SingleFlightCoordinator delegate) {
            this.delegate = delegate;
        }

        @Override
        public <T> CompletableFuture<T> execute(
                String key,
                java.util.function.Supplier<CompletableFuture<T>> operation,
                SingleFlightOptions options) {
            return delegate.execute(key, operation, options);
        }

        @Override
        public synchronized List<com.portfolio.singleflight.coordinator.InflightEntry> getInflightState() {
            if (firstCall) {
                firstCall = false;
                throw new RuntimeException("simulated heartbeat scan inner failure");
            }
            return delegate.getInflightState();
        }

        @Override
        public CompletableFuture<Void> forceRelease(String key, String reason) {
            return delegate.forceRelease(key, reason);
        }
    }
}
