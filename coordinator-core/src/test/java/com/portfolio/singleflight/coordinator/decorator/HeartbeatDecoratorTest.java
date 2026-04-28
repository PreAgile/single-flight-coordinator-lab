package com.portfolio.singleflight.coordinator.decorator;

import com.portfolio.singleflight.coordinator.InflightEntry;
import com.portfolio.singleflight.coordinator.SingleFlightCoordinator;
import com.portfolio.singleflight.coordinator.SingleFlightOptions;
import com.portfolio.singleflight.coordinator.adapter.InProcessSingleFlightCoordinator;
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
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

/**
 * {@link HeartbeatDecorator}의 행동 계약(behavioral contract).
 *
 * <p>이 데코레이터는 critical path에는 끼어들지 않는 순수 관측성(observability)
 * 컴포넌트다. {@code execute()}는 inner에 그대로 위임되며, 진짜 일은 별도
 * 스케줄러 스레드의 주기적 {@code scan()}에서 일어난다 — 그 결과로
 * {@code singleflight.inflight_count} gauge와
 * {@code singleflight.long_running} counter가 emit된다.
 *
 * <h2>변경자에게 — 이 테스트는 계약이다</h2>
 * <p>이 클래스(또는 의존 클래스)를 수정하는 사람은 모든 테스트가 초록색인 상태로
 * PR을 올려야 한다. 하나라도 빨간색이면 heartbeat이 약속한 관측성/수명주기/
 * 격리 보장 중 하나가 깨진 것이다. 의도된 계약 변경이라면 이 테스트도 함께
 * 갱신하고 PR 설명에 변경 사유를 남긴다.
 *
 * <h2>이 데코레이터가 보장하는 6가지 속성</h2>
 * <ol>
 *   <li><b>패스스루:</b> {@code execute()}는 inner의 결과(성공/실패)를 변형 없이
 *       그대로 전달하며, 그 자체로는 어떤 metric도 emit하지 않는다.</li>
 *   <li><b>long_running 감지:</b> {@code scan()}은 매번 inflight count gauge를
 *       emit하고, 시작 후 {@code longRunningThreshold} 이상 경과한 entry에 대해
 *       counter를 emit한다 — 미만이면 emit하지 않는다.</li>
 *   <li><b>스캔 회복력:</b> {@code scan()} 내부에서 inner가 throw해도 heartbeat은
 *       죽지 않으며, 다음 tick에 정상 동작한다 — 관측성은 critical path를
 *       흔들 수 없다.</li>
 *   <li><b>주기 + 수명주기:</b> 생성자에 받은 {@code scanInterval}에 맞춰 자동으로
 *       반복 scan이 일어나고, {@code close()}는 그 timer를 깔끔히 멈추며 멱등이다
 *       (여러 번 호출해도 안전).</li>
 *   <li><b>위임:</b> {@code getInflightState()}와 {@code forceRelease()}는 inner에
 *       그대로 전달된다.</li>
 *   <li><b>입력 계약:</b> 생성자의 {@code inner}/{@code scanInterval}/
 *       {@code longRunningThreshold}/{@code metrics} 인자가 null이면 NPE로
 *       즉시 거부된다 (fail-fast).</li>
 * </ol>
 *
 * <h2>그룹별 단일 관심사 원칙</h2>
 * <p>각 {@code @Nested} 그룹의 테스트는 자기 그룹의 보장 속성 <b>하나</b>에만
 * 집중한다. inflight 맵 cleanup은 본 데코레이터의 책임이 아니라 base 어댑터의
 * 책임이므로 여기서는 명세하지 않는다 — base 측의
 * {@code InProcessSingleFlightCoordinatorTest}에 [3] 자원 정리 그룹이 있다.
 * 본 테스트는 매달린 future를 끝맺을 때 {@code hangingFuture.complete(...)}만
 * 호출하고 후속 cleanup은 단언하지 않는다.
 */
@DisplayName("HeartbeatDecorator — execute에는 무영향이며 별도 스케줄러로 long-running entry를 관측 노출하는 데코레이터")
class HeartbeatDecoratorTest {

    private static final String KEY = "k";

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

    // ====================================================================
    // [1] 패스스루
    // ====================================================================
    @Nested
    @DisplayName("[1] 패스스루 — execute는 inner의 결과를 변형 없이 그대로 전달하며 자체적으로 metric을 emit하지 않는다")
    class PassThrough {

        @Test
        @DisplayName("inner가 성공값을 돌려주면 execute도 같은 값을 그대로 돌려준다")
        void executeReturnsInnerSuccessUnchanged() throws Exception {
            heartbeat = newQuietHeartbeat();

            String result = heartbeat.execute(KEY, () -> CompletableFuture.completedFuture("v"))
                    .get(1, TimeUnit.SECONDS);

            assertThat(result).isEqualTo("v");
        }

        @Test
        @DisplayName("inner가 예외로 실패하면 execute도 같은 cause로 실패한다 — heartbeat이 결과 채널을 변형하지 않음")
        void executePropagatesInnerFailureUnchanged() {
            heartbeat = newQuietHeartbeat();
            RuntimeException cause = new RuntimeException("downstream-broke");

            CompletableFuture<String> result = heartbeat.execute(
                    KEY, () -> CompletableFuture.failedFuture(cause));

            assertThatThrownBy(() -> result.get(1, TimeUnit.SECONDS))
                    .isInstanceOf(ExecutionException.class)
                    .hasCauseReference(cause);
        }

        @Test
        @DisplayName("execute 호출 자체로는 어떤 metric도 emit하지 않는다 — 관측은 scan의 책임이지 execute의 책임이 아니다")
        void executeAloneDoesNotEmitMetrics() throws Exception {
            heartbeat = newQuietHeartbeat();

            heartbeat.execute(KEY, () -> CompletableFuture.completedFuture("v"))
                    .get(1, TimeUnit.SECONDS);

            assertThat(metrics.totalEmits())
                    .as("execute는 metric에 흔적을 남기지 않아야 한다")
                    .isZero();
        }
    }

    // ====================================================================
    // [2] long_running 감지
    // ====================================================================
    @Nested
    @DisplayName("[2] long_running 감지 — scan은 inflight count gauge를 매번 emit하고 threshold 이상 entry에 대해서만 long_running counter를 emit한다")
    class LongRunningDetection {

        @Test
        @DisplayName("threshold=0이면 매달린 entry 1개당 long_running counter가 emit되고 inflight gauge는 1.0으로 기록된다")
        void thresholdZeroFlagsEveryEntry() {
            heartbeat = new HeartbeatDecorator(
                    base, Duration.ofMinutes(10), Duration.ZERO, metrics);

            CompletableFuture<String> hanging = new CompletableFuture<>();
            heartbeat.execute("stuck", () -> hanging);

            heartbeat.scan();

            assertThat(metrics.counterCalls("singleflight.long_running")).isPositive();
            assertThat(metrics.lastGauge("singleflight.inflight_count")).isEqualTo(1.0);

            hanging.complete("done");
        }

        @Test
        @DisplayName("threshold가 entry age보다 크면 long_running counter는 emit되지 않지만 inflight gauge는 그대로 emit된다")
        void thresholdAboveAgeSkipsCounterButStillEmitsGauge() {
            // counter는 "오래 걸리는 작업"이라는 정량 신호, gauge는 "지금 몇 개 떠있는지"라는
            // 무조건 신호 — 둘의 트리거 조건이 다르다는 점을 분리해 명세한다.
            heartbeat = new HeartbeatDecorator(
                    base, Duration.ofMinutes(10), Duration.ofMinutes(1), metrics);

            CompletableFuture<String> hanging = new CompletableFuture<>();
            heartbeat.execute("fresh", () -> hanging);

            heartbeat.scan();

            assertThat(metrics.counterCalls("singleflight.long_running"))
                    .as("entry age는 ms 수준, threshold는 1분 — 절대 넘을 수 없다")
                    .isZero();
            assertThat(metrics.lastGauge("singleflight.inflight_count")).isEqualTo(1.0);

            hanging.complete("done");
        }

        @Test
        @DisplayName("inflight가 비어 있으면 long_running counter는 emit되지 않고 inflight gauge는 0으로 기록된다")
        void emptyInflightEmitsZeroGaugeAndNoCounter() {
            heartbeat = new HeartbeatDecorator(
                    base, Duration.ofMinutes(10), Duration.ZERO, metrics);

            heartbeat.scan();

            assertThat(metrics.counterCalls("singleflight.long_running")).isZero();
            assertThat(metrics.lastGauge("singleflight.inflight_count")).isZero();
        }
    }

    // ====================================================================
    // [3] 스캔 회복력
    // ====================================================================
    @Nested
    @DisplayName("[3] 스캔 회복력 — scan 도중 inner가 throw해도 heartbeat은 죽지 않으며 다음 tick에 정상 복귀한다")
    class ScanResilience {

        @Test
        @DisplayName("inner.getInflightState()가 한 번 throw해도 다음 scan은 정상적으로 inflight gauge를 emit한다")
        void innerThrowingOnceDoesNotKillHeartbeat() {
            // 관측성이 critical path를 흔들면 안 된다는 계약. scheduleAtFixedRate는
            // 한 번 task가 throw하면 후속 발화를 멈춰버리는 무서운 default가 있어
            // (Javadoc 참고), HeartbeatDecorator.scan()이 try/catch로 swallow하는
            // 책임을 진다. 이 테스트는 그 책임을 박아넣는다.
            ThrowOnceCoordinator flaky = new ThrowOnceCoordinator(base);
            heartbeat = new HeartbeatDecorator(
                    flaky, Duration.ofMinutes(10), Duration.ZERO, metrics);

            CompletableFuture<String> hanging = new CompletableFuture<>();
            heartbeat.execute(KEY, () -> hanging);

            heartbeat.scan(); // throw — swallow 되어야 함
            heartbeat.scan(); // 정상 복귀

            assertThat(metrics.lastGauge("singleflight.inflight_count")).isEqualTo(1.0);

            hanging.complete("done");
        }
    }

    // ====================================================================
    // [4] 주기 + 수명주기
    // ====================================================================
    @Nested
    @DisplayName("[4] 주기 + 수명주기 — scanInterval에 맞춰 자동 scan이 반복되고 close()는 timer를 깔끔히 멈추며 멱등이다")
    class ScheduleAndLifecycle {

        @Test
        @DisplayName("짧은 scanInterval이면 별도 호출 없이도 inflight gauge가 여러 번 자동으로 emit된다")
        void timerAutomaticallyTicksWithoutManualScan() {
            heartbeat = new HeartbeatDecorator(
                    base, Duration.ofMillis(20), Duration.ofMinutes(1), metrics);

            awaitGaugeCallsAtLeast(2);
        }

        @Test
        @DisplayName("close() 이후에는 새 tick이 더 이상 기록되지 않는다 — close와 동시 발화 1건은 race로 허용")
        void closeStopsFurtherTicks() {
            heartbeat = new HeartbeatDecorator(
                    base, Duration.ofMillis(20), Duration.ofMinutes(1), metrics);

            awaitGaugeCallsAtLeast(2);

            long countAtClose = metrics.gaugeCalls("singleflight.inflight_count");
            heartbeat.close();

            await().pollDelay(Duration.ofMillis(200)).atMost(500, TimeUnit.MILLISECONDS)
                    .untilAsserted(() -> assertThat(
                            metrics.gaugeCalls("singleflight.inflight_count") - countAtClose)
                            .as("close 이후 추가 tick은 최대 1건(race)")
                            .isLessThanOrEqualTo(1));
        }

        @Test
        @DisplayName("close()를 여러 번 호출해도 예외 없이 멱등하게 동작한다")
        void closeIsIdempotent() {
            heartbeat = new HeartbeatDecorator(
                    base, Duration.ofMillis(20), Duration.ofMinutes(1), metrics);

            heartbeat.close();
            heartbeat.close();
            heartbeat.close();
        }
    }

    // ====================================================================
    // [5] 위임
    // ====================================================================
    @Nested
    @DisplayName("[5] 위임 — getInflightState/forceRelease는 inner 코디네이터에 그대로 전달된다")
    class Delegation {

        @Test
        @DisplayName("getInflightState()는 inner의 스냅샷과 동일한 결과를 반환한다")
        void getInflightStateDelegatesToInner() {
            heartbeat = newQuietHeartbeat();

            CompletableFuture<String> hanging = new CompletableFuture<>();
            heartbeat.execute(KEY, () -> hanging);

            await().atMost(1, TimeUnit.SECONDS)
                    .until(() -> base.getInflightState().size() == 1);

            assertThat(heartbeat.getInflightState()).isEqualTo(base.getInflightState());

            hanging.complete("done");
        }

        @Test
        @DisplayName("forceRelease는 inner를 거쳐 매달린 호출자에게 ForceReleasedException을 전파한다")
        void forceReleaseDelegatesToInner() throws Exception {
            heartbeat = newQuietHeartbeat();

            CompletableFuture<String> hanging = new CompletableFuture<>();
            CompletableFuture<String> r = heartbeat.execute(KEY, () -> hanging);

            await().atMost(1, TimeUnit.SECONDS)
                    .until(() -> base.getInflightState().size() == 1);

            heartbeat.forceRelease(KEY, "operator-test").get(1, TimeUnit.SECONDS);

            assertThatThrownBy(() -> r.get(1, TimeUnit.SECONDS))
                    .isInstanceOf(ExecutionException.class)
                    .hasCauseInstanceOf(ForceReleasedException.class);
        }
    }

    // ====================================================================
    // [6] 입력 계약
    // ====================================================================
    @Nested
    @DisplayName("[6] 입력 계약 — 생성자의 null 인자는 NPE로 즉시 거부된다 (fail-fast)")
    class InputContract {

        @Test
        @DisplayName("new HeartbeatDecorator(null, ...) → NPE(\"inner\")")
        void rejectsNullInner() {
            assertThatThrownBy(() -> new HeartbeatDecorator(
                    null, Duration.ofSeconds(1), Duration.ofSeconds(1), metrics))
                    .isInstanceOf(NullPointerException.class)
                    .hasMessage("inner");
        }

        @Test
        @DisplayName("new HeartbeatDecorator(_, null, _, _) → NPE(\"scanInterval\")")
        void rejectsNullScanInterval() {
            assertThatThrownBy(() -> new HeartbeatDecorator(
                    base, null, Duration.ofSeconds(1), metrics))
                    .isInstanceOf(NullPointerException.class)
                    .hasMessage("scanInterval");
        }

        @Test
        @DisplayName("new HeartbeatDecorator(_, _, null, _) → NPE(\"longRunningThreshold\")")
        void rejectsNullLongRunningThreshold() {
            assertThatThrownBy(() -> new HeartbeatDecorator(
                    base, Duration.ofSeconds(1), null, metrics))
                    .isInstanceOf(NullPointerException.class)
                    .hasMessage("longRunningThreshold");
        }

        @Test
        @DisplayName("new HeartbeatDecorator(_, _, _, null) → NPE(\"metrics\")")
        void rejectsNullMetrics() {
            assertThatThrownBy(() -> new HeartbeatDecorator(
                    base, Duration.ofSeconds(1), Duration.ofSeconds(1), null))
                    .isInstanceOf(NullPointerException.class)
                    .hasMessage("metrics");
        }
    }

    // ====================================================================
    // Helpers — fixture, metric sink, scan-resilience용 test double
    // ====================================================================

    /**
     * 자동 tick이 사실상 일어나지 않는 fixture. [1] 패스스루, [5] 위임처럼
     * "scan과 무관한" 보장을 검증하는 그룹이 자동 발화된 metric에 오염되지
     * 않도록 scanInterval/threshold를 둘 다 길게 잡는다.
     */
    private HeartbeatDecorator newQuietHeartbeat() {
        return new HeartbeatDecorator(
                base, Duration.ofMinutes(10), Duration.ofMinutes(10), metrics);
    }

    private void awaitGaugeCallsAtLeast(long minCalls) {
        await().atMost(500, TimeUnit.MILLISECONDS).untilAsserted(() ->
                assertThat(metrics.gaugeCalls("singleflight.inflight_count"))
                        .isGreaterThanOrEqualTo(minCalls));
    }

    /**
     * 이름 기반으로 정직하게 분리되는 test sink. 이전 버전이 쓰던
     * {@code gaugeCalls(String ignoredName)} 같은 거짓 시그니처는 metric 이름
     * 변경/오타 회귀를 잡지 못하므로 의도적으로 폐기.
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
            histograms.add(new RecordedSample(name, value));
        }

        @Override
        public synchronized void recordGauge(String name, double value, Map<String, String> tags) {
            gauges.add(new RecordedSample(name, value));
        }

        synchronized long counterCalls(String name) {
            return counterNames.stream().filter(name::equals).count();
        }

        synchronized long gaugeCalls(String name) {
            return gauges.stream().filter(g -> g.name.equals(name)).count();
        }

        synchronized double lastGauge(String name) {
            return gauges.stream()
                    .filter(g -> g.name.equals(name))
                    .reduce((a, b) -> b)
                    .map(RecordedSample::value)
                    .orElseThrow(() -> new AssertionError(
                            "no gauge sample recorded for " + name));
        }

        synchronized int totalEmits() {
            return counterNames.size() + gauges.size() + histograms.size();
        }

        private record RecordedSample(String name, double value) {}
    }

    /**
     * [3] 스캔 회복력 전용 test double — 첫 {@code getInflightState()} 호출에서만
     * throw해서 "한 번 실패 → 다음 tick 정상 복귀" 시나리오를 deterministic하게
     * 재현한다.
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
                Supplier<CompletableFuture<T>> operation,
                SingleFlightOptions options) {
            return delegate.execute(key, operation, options);
        }

        @Override
        public synchronized List<InflightEntry> getInflightState() {
            if (firstCall) {
                firstCall = false;
                throw new RuntimeException("simulated inner failure");
            }
            return delegate.getInflightState();
        }

        @Override
        public CompletableFuture<Void> forceRelease(String key, String reason) {
            return delegate.forceRelease(key, reason);
        }
    }
}
