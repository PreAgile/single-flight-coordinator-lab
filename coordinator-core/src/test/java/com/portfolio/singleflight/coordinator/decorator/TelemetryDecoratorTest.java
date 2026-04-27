package com.portfolio.singleflight.coordinator.decorator;

import com.portfolio.singleflight.coordinator.SingleFlightCoordinator;
import com.portfolio.singleflight.coordinator.adapter.InProcessSingleFlightCoordinator;
import com.portfolio.singleflight.coordinator.exception.CongestionException;
import com.portfolio.singleflight.coordinator.exception.DeadlineExceededException;
import com.portfolio.singleflight.coordinator.exception.ForceReleasedException;
import com.portfolio.singleflight.coordinator.observability.MetricSink;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

/**
 * {@link TelemetryDecorator}의 행동 계약(behavioral contract).
 *
 * <p>이 데코레이터는 canonical decorator stack의 <b>가장 바깥(outermost)</b>에
 * 위치해 inner chain이 만들어내는 모든 결과 — 정상 완료, 일반 실패, deadline,
 * congestion, force_release — 를 단일 {@code status} 태그로 분류해 histogram
 * metric으로 emit한다. outermost가 아니면 {@link DeadlineExceededException}이나
 * {@link CongestionException} 같이 inner layer가 만든 새 예외 타입이 telemetry
 * 시야 밖에서 처리되어 분류 손실이 발생한다 — 그래서 분류는 본질적으로
 * "outermost 위치에서만 정확히 동작하는 책임"이다.
 *
 * <h2>변경자에게 — 이 테스트는 계약이다</h2>
 * <p>이 클래스(또는 의존 클래스)를 수정하는 사람은 모든 테스트가 초록색인 상태로
 * PR을 올려야 한다. 하나라도 빨간색이면 telemetry가 약속한 분류 기준 또는
 * 결과 패스스루 보장 중 하나가 깨진 것이다. 의도된 계약 변경이라면 이 테스트도
 * 함께 갱신하고 PR 설명에 변경 사유를 남긴다.
 *
 * <h2>이 데코레이터가 보장하는 8가지 속성</h2>
 * <ol>
 *   <li><b>success 분류:</b> inner future가 정상 완료되면
 *       {@code status=success} 태그로 {@code singleflight.owner_duration_ms}
 *       histogram이 비음수 duration과 함께 emit된다.</li>
 *   <li><b>failure 분류:</b> inner future가 일반 {@link RuntimeException}으로
 *       실패하면 {@code status=failure}로 분류되며, 실패 경로에서도 duration은
 *       기록된다 (측정이 끊기지 않음).</li>
 *   <li><b>deadline 분류:</b> inner future의 cause가
 *       {@link DeadlineExceededException}이면 {@code CompletionException} /
 *       {@code ExecutionException} wrapping을 unwrap한 뒤
 *       {@code status=deadline}으로 분류한다.</li>
 *   <li><b>congestion 분류:</b> inner가 {@link CongestionException}을 동기로
 *       throw하는 경로(base 어댑터의 원자적 cap 거부)도 try/catch로 잡혀
 *       {@code status=congestion}으로 기록된 뒤 호출자에게 그대로 재전파된다 —
 *       {@code whenComplete}만으로는 sync throw를 잡을 수 없다는 사실을
 *       회귀에 박아둔다.</li>
 *   <li><b>패스스루(value/cause):</b> 정상 결과의 값과 실패의 원인 객체는
 *       telemetry를 거치며 변형되지 않고 호출자에게 그대로 전달된다.</li>
 *   <li><b>위임:</b> {@code getInflightState}와 {@code forceRelease}는 inner에
 *       그대로 위임되며 force_release 시 매달린 호출자는
 *       {@link ForceReleasedException}을 받는다.</li>
 *   <li><b>입력 계약:</b> 생성자의 {@code inner}/{@code metrics} 인자가
 *       null이면 NPE로 즉시 거부된다 (fail-fast).</li>
 *   <li><b>metric 이름 계약:</b> emit하는 histogram metric 이름은 정확히
 *       {@code "singleflight.owner_duration_ms"}이다 — 이름 변경/오타에 대한
 *       회귀 차단.</li>
 * </ol>
 *
 * <h2>그룹별 단일 관심사 원칙</h2>
 * <p>각 {@code @Nested} 그룹의 테스트는 자기 그룹의 보장 속성 <b>하나</b>에만
 * 집중한다. status 분류 4가지를 [1]~[4]로 따로 그룹화한 이유는 한 status
 * 분류가 깨졌을 때 어느 그룹이 빨간색인지 그룹명에 즉시 보이도록 하기 위함이다.
 * inflight 맵 cleanup은 본 데코레이터의 책임이 아니라 base 어댑터의 책임이므로
 * 여기서는 명세하지 않는다 — base 측의
 * {@code InProcessSingleFlightCoordinatorTest}에 [3] 자원 정리 그룹이 있다.
 */
@DisplayName("TelemetryDecorator — 가장 바깥 layer에서 모든 결과를 status 태그로 분류해 owner_duration_ms histogram을 emit하는 데코레이터")
class TelemetryDecoratorTest {

    private static final String METRIC_NAME = "singleflight.owner_duration_ms";
    private static final String KEY = "k";

    private InProcessSingleFlightCoordinator base;
    private RecordingMetricSink metrics;

    @BeforeEach
    void setUp() {
        base = new InProcessSingleFlightCoordinator();
        metrics = new RecordingMetricSink();
    }

    // ====================================================================
    // [1] success 분류
    // ====================================================================
    @Nested
    @DisplayName("[1] success 분류 — inner future가 정상 완료되면 status=success 태그로 비음수 duration이 기록된다")
    class SuccessClassification {

        @Test
        @DisplayName("inner가 성공값을 돌려주면 histogram 마지막 sample의 status 태그는 success다")
        void successTagsHistogramAsSuccess() throws Exception {
            SingleFlightCoordinator coord = new TelemetryDecorator(base, metrics);

            coord.execute(KEY, () -> CompletableFuture.completedFuture("v"))
                    .get(1, TimeUnit.SECONDS);

            assertThat(metrics.histogramTagsLast(METRIC_NAME))
                    .containsEntry("status", "success");
        }

        @Test
        @DisplayName("성공 경로의 histogram value는 비음수다 — duration은 ms 단위로 0 이상이어야 한다")
        void successDurationIsNonNegative() throws Exception {
            SingleFlightCoordinator coord = new TelemetryDecorator(base, metrics);

            coord.execute(KEY, () -> CompletableFuture.completedFuture("v"))
                    .get(1, TimeUnit.SECONDS);

            assertThat(metrics.histogramValueLast(METRIC_NAME)).isNotNegative();
        }
    }

    // ====================================================================
    // [2] failure 분류
    // ====================================================================
    @Nested
    @DisplayName("[2] failure 분류 — inner future가 일반 RuntimeException으로 실패하면 status=failure로 기록되고 duration도 기록된다")
    class FailureClassification {

        @Test
        @DisplayName("inner가 RuntimeException으로 실패하면 histogram의 status 태그는 failure다")
        void failureTagsHistogramAsFailure() {
            SingleFlightCoordinator coord = new TelemetryDecorator(base, metrics);

            CompletableFuture<String> result = coord.execute(
                    KEY, () -> CompletableFuture.failedFuture(new RuntimeException("oops")));

            assertThatThrownBy(() -> result.get(1, TimeUnit.SECONDS))
                    .isInstanceOf(ExecutionException.class);
            assertThat(metrics.histogramTagsLast(METRIC_NAME))
                    .containsEntry("status", "failure");
        }

        @Test
        @DisplayName("실패 경로에서도 duration histogram은 정확히 한 번 emit된다 — 실패라고 측정이 끊기지 않는다")
        void failurePathStillEmitsDuration() {
            SingleFlightCoordinator coord = new TelemetryDecorator(base, metrics);

            CompletableFuture<String> result = coord.execute(
                    KEY, () -> CompletableFuture.failedFuture(new RuntimeException("oops")));

            assertThatThrownBy(() -> result.get(1, TimeUnit.SECONDS))
                    .isInstanceOf(ExecutionException.class);
            assertThat(metrics.histogramCalls(METRIC_NAME)).isEqualTo(1);
            assertThat(metrics.histogramValueLast(METRIC_NAME)).isNotNegative();
        }
    }

    // ====================================================================
    // [3] deadline 분류
    // ====================================================================
    @Nested
    @DisplayName("[3] deadline 분류 — inner cause가 DeadlineExceededException이면 wrapping을 unwrap해 status=deadline으로 분류한다")
    class DeadlineClassification {

        @Test
        @DisplayName("Deadline이 inner에 있어 outermost telemetry가 future failure로 받을 때도 cause를 unwrap해 status=deadline으로 분류한다")
        void deadlineFromInnerLayerIsClassifiedAsDeadline() {
            // canonical stack: Telemetry(outermost) → Deadline → base. 그래서
            // telemetry는 DeadlineExceededException을 future failure 형태로 본다 —
            // CompletionException 안에 cause로 들어 있다. unwrap이 작동해야 분류가 맞음.
            SingleFlightCoordinator withDeadline = new DeadlineDecorator(base, 50);
            SingleFlightCoordinator coord = new TelemetryDecorator(withDeadline, metrics);

            CompletableFuture<String> result = coord.execute(KEY, CompletableFuture::new);

            assertThatThrownBy(() -> result.get(2, TimeUnit.SECONDS))
                    .isInstanceOf(ExecutionException.class)
                    .hasCauseInstanceOf(DeadlineExceededException.class);
            assertThat(metrics.histogramTagsLast(METRIC_NAME))
                    .containsEntry("status", "deadline");
        }
    }

    // ====================================================================
    // [4] congestion 분류
    // ====================================================================
    @Nested
    @DisplayName("[4] congestion 분류 — base의 동기 throw도 try/catch로 잡혀 status=congestion으로 기록된 뒤 그대로 재전파된다")
    class CongestionClassification {

        @Test
        @DisplayName("base가 CongestionException을 sync throw하면 telemetry가 try/catch로 잡아 status=congestion 기록 후 재전파한다 — whenComplete만으로는 sync throw를 못 잡는다는 사실을 박아둔다")
        void synchronousCongestionIsCaughtAndClassified() {
            SingleFlightCoordinator withCapacity = new CapacityDecorator(base, 1);
            SingleFlightCoordinator coord = new TelemetryDecorator(withCapacity, metrics);

            CompletableFuture<String> hanging = new CompletableFuture<>();
            coord.execute(KEY, () -> hanging); // owner — fills cap

            assertThatThrownBy(() -> coord.execute(KEY, () -> hanging))
                    .isInstanceOf(CongestionException.class);
            assertThat(metrics.histogramTagsLast(METRIC_NAME))
                    .containsEntry("status", "congestion");

            hanging.complete("done");
        }

        @Test
        @DisplayName("congestion 거부도 duration histogram을 정확히 한 번 emit한다 — sync throw 경로에서도 측정이 빠지지 않는다")
        void synchronousCongestionStillEmitsDurationOnce() {
            SingleFlightCoordinator withCapacity = new CapacityDecorator(base, 1);
            SingleFlightCoordinator coord = new TelemetryDecorator(withCapacity, metrics);

            CompletableFuture<String> hanging = new CompletableFuture<>();
            coord.execute(KEY, () -> hanging); // owner emits 0 metrics yet (still inflight)

            assertThatThrownBy(() -> coord.execute(KEY, () -> hanging))
                    .isInstanceOf(CongestionException.class);

            // owner는 아직 inflight라 metric 1건은 거부된 호출 몫이어야 한다.
            assertThat(metrics.histogramCalls(METRIC_NAME)).isEqualTo(1);
            assertThat(metrics.histogramValueLast(METRIC_NAME)).isNotNegative();

            hanging.complete("done");
        }
    }

    // ====================================================================
    // [5] 패스스루(value/cause)
    // ====================================================================
    @Nested
    @DisplayName("[5] 패스스루 — telemetry는 결과 채널을 변형하지 않는다: 정상값/실패 cause 모두 inner와 동일하게 호출자에게 전달된다")
    class ResultPassThrough {

        @Test
        @DisplayName("inner의 성공값은 telemetry를 거쳐도 변형 없이 그대로 호출자에게 전달된다")
        void successValueIsForwardedUnchanged() throws Exception {
            SingleFlightCoordinator coord = new TelemetryDecorator(base, metrics);

            String result = coord.execute(KEY, () -> CompletableFuture.completedFuture("v"))
                    .get(1, TimeUnit.SECONDS);

            assertThat(result).isEqualTo("v");
        }

        @Test
        @DisplayName("inner의 실패 cause 객체는 telemetry를 거쳐도 같은 인스턴스 그대로 호출자의 ExecutionException.getCause()로 전달된다")
        void failureCauseIsForwardedUnchanged() {
            SingleFlightCoordinator coord = new TelemetryDecorator(base, metrics);
            RuntimeException cause = new RuntimeException("downstream-broke");

            CompletableFuture<String> result = coord.execute(
                    KEY, () -> CompletableFuture.failedFuture(cause));

            assertThatThrownBy(() -> result.get(1, TimeUnit.SECONDS))
                    .isInstanceOf(ExecutionException.class)
                    .hasCauseReference(cause);
        }
    }

    // ====================================================================
    // [6] 위임
    // ====================================================================
    @Nested
    @DisplayName("[6] 위임 — getInflightState/forceRelease는 inner 코디네이터에 그대로 전달된다")
    class Delegation {

        @Test
        @DisplayName("getInflightState()는 inner의 스냅샷과 동일한 결과를 반환한다")
        void getInflightStateDelegatesToInner() {
            SingleFlightCoordinator coord = new TelemetryDecorator(base, metrics);

            CompletableFuture<String> hanging = new CompletableFuture<>();
            coord.execute(KEY, () -> hanging);

            awaitSingleInflight();

            assertThat(coord.getInflightState()).isEqualTo(base.getInflightState());

            hanging.complete("done");
        }

        @Test
        @DisplayName("forceRelease는 inner를 거쳐 매달린 호출자에게 ForceReleasedException을 전파한다")
        void forceReleaseDelegatesToInner() throws Exception {
            SingleFlightCoordinator coord = new TelemetryDecorator(base, metrics);

            CompletableFuture<String> hanging = new CompletableFuture<>();
            CompletableFuture<String> r = coord.execute(KEY, () -> hanging);

            awaitSingleInflight();

            coord.forceRelease(KEY, "operator-test").get(1, TimeUnit.SECONDS);

            assertThatThrownBy(() -> r.get(1, TimeUnit.SECONDS))
                    .isInstanceOf(ExecutionException.class)
                    .hasCauseInstanceOf(ForceReleasedException.class);
        }
    }

    // ====================================================================
    // [7] 입력 계약
    // ====================================================================
    @Nested
    @DisplayName("[7] 입력 계약 — 생성자의 null 인자는 NPE로 즉시 거부된다 (fail-fast)")
    class InputContract {

        @Test
        @DisplayName("new TelemetryDecorator(null, metrics) → NPE(\"inner\")")
        void rejectsNullInner() {
            assertThatThrownBy(() -> new TelemetryDecorator(null, metrics))
                    .isInstanceOf(NullPointerException.class)
                    .hasMessage("inner");
        }

        @Test
        @DisplayName("new TelemetryDecorator(inner, null) → NPE(\"metrics\")")
        void rejectsNullMetrics() {
            assertThatThrownBy(() -> new TelemetryDecorator(base, null))
                    .isInstanceOf(NullPointerException.class)
                    .hasMessage("metrics");
        }
    }

    // ====================================================================
    // [8] metric 이름 계약
    // ====================================================================
    @Nested
    @DisplayName("[8] metric 이름 계약 — emit하는 histogram의 metric name은 정확히 \"singleflight.owner_duration_ms\"이다")
    class MetricNameContract {

        @Test
        @DisplayName("정확한 metric name으로만 histogram이 emit된다 — 이름 변경/오타 회귀 차단")
        void exactMetricNameIsUsed() throws Exception {
            SingleFlightCoordinator coord = new TelemetryDecorator(base, metrics);

            coord.execute(KEY, () -> CompletableFuture.completedFuture("v"))
                    .get(1, TimeUnit.SECONDS);

            assertThat(metrics.allHistogramNames())
                    .as("emit된 histogram은 모두 정확한 이름을 사용해야 한다")
                    .containsOnly(METRIC_NAME);
        }
    }

    // ====================================================================
    // Helpers — base inflight 동기화 + 이름 기반 metric sink
    // ====================================================================

    private void awaitSingleInflight() {
        await().atMost(1, TimeUnit.SECONDS)
                .until(() -> base.getInflightState().size() == 1);
    }

    /**
     * 이름 기반으로 정직하게 분리되는 test sink — counter / gauge / histogram을
     * 모두 기록한다. {@code histogramTagsLast(name)}는 같은 이름의 마지막
     * sample의 tag map을 그대로 노출해 호출자가 원하는 키로 단언할 수 있게 한다
     * (last-wins reduce는 다중 호출 시에도 가장 최근 분류를 정확히 보여준다).
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

        synchronized long counterCalls(String name) {
            return counterNames.stream().filter(name::equals).count();
        }

        synchronized long histogramCalls(String name) {
            return histograms.stream().filter(s -> s.name.equals(name)).count();
        }

        synchronized long gaugeCalls(String name) {
            return gauges.stream().filter(s -> s.name.equals(name)).count();
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

        synchronized List<String> allHistogramNames() {
            return histograms.stream().map(RecordedSample::name).distinct().toList();
        }

        private record RecordedSample(String name, double value, Map<String, String> tags) {}
    }
}
