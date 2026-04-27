package com.portfolio.singleflight.coordinator.decorator;

import com.portfolio.singleflight.coordinator.InflightEntry;
import com.portfolio.singleflight.coordinator.SingleFlightCoordinator;
import com.portfolio.singleflight.coordinator.SingleFlightOptions;
import com.portfolio.singleflight.coordinator.adapter.InProcessSingleFlightCoordinator;
import com.portfolio.singleflight.coordinator.exception.DeadlineExceededException;
import com.portfolio.singleflight.coordinator.exception.ForceReleasedException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

/**
 * {@link DeadlineDecorator}의 행동 계약(behavioral contract).
 *
 * <p>이 데코레이터는 thin policy injector이다. caller의 {@link SingleFlightOptions}에
 * {@code deadlineMs}가 비어 있을 때 자기가 보유한 기본값을 주입하고, 그 deadline을
 * inner의 operation future에 {@link CompletableFuture#orTimeout} 형태로 두른 뒤
 * {@link java.util.concurrent.TimeoutException}을 도메인 예외인
 * {@link DeadlineExceededException}으로 번역한다. 본 테스트는 그 정책 boundary와
 * 번역 책임을 실행 가능한 명세로 기술한다.
 *
 * <h2>변경자에게 — 이 테스트는 계약이다</h2>
 * <p>이 클래스(또는 의존 클래스)를 수정하는 사람은 모든 테스트가 초록색인 상태로
 * PR을 올려야 한다. 하나라도 빨간색이면 deadline 정책이 약속한 boundary 또는
 * 예외 번역 보장 중 하나가 깨진 것이다. 의도된 계약 변경이라면 이 테스트도 함께
 * 갱신하고 PR 설명에 변경 사유를 남긴다.
 *
 * <h2>이 데코레이터가 보장하는 6가지 속성</h2>
 * <ol>
 *   <li><b>기본 deadline 주입:</b> {@code defaultDeadlineMs > 0}이고 caller가
 *       options에 {@code deadlineMs}를 지정하지 않으면 기본값을 주입해 inner에
 *       위임하고, 시한이 지나면 {@link DeadlineExceededException}으로 실패한다.</li>
 *   <li><b>옵션 우선:</b> caller가 명시한 {@code options.deadlineMs}는 항상
 *       데코레이터의 기본값을 덮어쓴다 (강하게도 약하게도).</li>
 *   <li><b>비활성 모드:</b> {@code defaultDeadlineMs <= 0}이고 옵션도 없으면
 *       어떤 timeout도 두르지 않은 채 inner에 그대로 위임한다 (옵션 자체도
 *       그대로 — 변형 없음).</li>
 *   <li><b>정상 완료 패스스루:</b> 작업이 deadline 안에 끝나면 timeout 채널은
 *       정상 결과를 변형하지 않는다. 성공값/inner 실패 cause 모두 그대로 전파된다.</li>
 *   <li><b>위임:</b> {@code getInflightState}와 {@code forceRelease}는 inner
 *       코디네이터에 그대로 전달된다.</li>
 *   <li><b>입력 계약:</b> 생성자의 {@code inner}와 {@code execute()}의
 *       {@code options}가 null이면 NPE로 즉시 거부된다 (fail-fast).</li>
 * </ol>
 *
 * <h2>그룹별 단일 관심사 원칙</h2>
 * <p>각 {@code @Nested} 그룹의 테스트는 자기 그룹의 보장 속성 <b>하나</b>에만
 * 집중한다. 자원 정리(cleanup)는 본 데코레이터의 책임이 아니라 base 어댑터의
 * 책임이므로 여기서는 명세하지 않는다 — base 측의
 * {@code InProcessSingleFlightCoordinatorTest}에 [3] 자원 정리 그룹이 있다.
 * 본 테스트는 cleanup을 검증하지 않으며, 매달린 future를 끝맺을 때
 * {@code hangingFuture.complete(...)}만 호출한다.
 */
@DisplayName("DeadlineDecorator — caller가 옵션을 지정하지 않을 때 기본 deadline을 주입하고 timeout을 도메인 예외로 번역하는 데코레이터")
class DeadlineDecoratorTest {

    private static final String KEY = "k";

    private InProcessSingleFlightCoordinator inner;

    @BeforeEach
    void setUp() {
        inner = new InProcessSingleFlightCoordinator();
    }

    // ====================================================================
    // [1] 기본 deadline 주입
    // ====================================================================
    @Nested
    @DisplayName("[1] 기본 deadline 주입 — defaultDeadlineMs > 0이고 caller가 옵션을 지정하지 않으면 기본값을 적용한다")
    class DefaultDeadlineInjection {

        @Test
        @DisplayName("default=50ms이고 caller가 옵션 미지정이면 영원히 안 끝나는 작업은 DeadlineExceededException으로 실패한다")
        void defaultDeadlineFiresWhenNoCallerOption() {
            SingleFlightCoordinator coord = new DeadlineDecorator(inner, 50);

            CompletableFuture<String> result = coord.execute(KEY, CompletableFuture::new);

            assertThatThrownBy(() -> result.get(2, TimeUnit.SECONDS))
                    .isInstanceOf(ExecutionException.class)
                    .hasCauseInstanceOf(DeadlineExceededException.class);
        }

        @Test
        @DisplayName("default=50이고 caller가 옵션 미지정이면 inner에 전달되는 effective options.deadlineMs는 50이다 — option injection 직접 검증")
        void defaultDeadlineIsInjectedIntoEffectiveOptions() {
            // 통합 검증(timeout 발화 → 추론)이 아니라 inner로 흘러가는 옵션 자체를 확인.
            // 데코레이터의 본질적 책임("default 값을 effective options에 주입")이
            // 결과 동작과 분리되어 명세된다.
            RecordingCoordinator recorder = new RecordingCoordinator();
            SingleFlightCoordinator coord = new DeadlineDecorator(recorder, 50);

            coord.execute(KEY, () -> CompletableFuture.completedFuture("v"));

            assertThat(recorder.lastOptions).isNotNull();
            assertThat(recorder.lastOptions.deadlineMs()).hasValue(50);
        }

        @Test
        @DisplayName("DeadlineExceededException은 key/deadlineMs/telemetryTag/cause를 모두 노출한다")
        void exceptionExposesAllDiagnosticFields() {
            SingleFlightCoordinator coord = new DeadlineDecorator(inner, 50);
            SingleFlightOptions opts = SingleFlightOptions.builder()
                    .telemetryTag("naver-search")
                    .build();

            CompletableFuture<String> result = coord.execute(
                    KEY, CompletableFuture::new, opts);

            assertThatThrownBy(() -> result.get(2, TimeUnit.SECONDS))
                    .isInstanceOf(ExecutionException.class)
                    .hasCauseInstanceOf(DeadlineExceededException.class)
                    .satisfies(t -> {
                        DeadlineExceededException ex = (DeadlineExceededException) t.getCause();
                        assertThat(ex.key()).isEqualTo(KEY);
                        assertThat(ex.deadlineMs()).isEqualTo(50);
                        assertThat(ex.telemetryTag()).isEqualTo("naver-search");
                        assertThat(ex.getCause())
                                .as("원래 TimeoutException은 cause로 보존된다")
                                .isInstanceOf(java.util.concurrent.TimeoutException.class);
                    });
        }
    }

    // ====================================================================
    // [2] 옵션 우선
    // ====================================================================
    @Nested
    @DisplayName("[2] 옵션 우선 — caller가 명시한 options.deadlineMs는 항상 데코레이터 기본값을 덮어쓴다")
    class OptionOverride {

        @Test
        @DisplayName("default=5000이지만 caller가 deadlineMs=50으로 지정하면 50ms 안에 DeadlineExceededException이 발생한다 (strict 방향)")
        void callerOptionCanLowerBelowDefault() {
            SingleFlightCoordinator coord = new DeadlineDecorator(inner, 5_000);
            SingleFlightOptions tightOpts = SingleFlightOptions.builder().deadlineMs(50).build();

            CompletableFuture<String> result = coord.execute(KEY, CompletableFuture::new, tightOpts);

            assertThatThrownBy(() -> result.get(2, TimeUnit.SECONDS))
                    .isInstanceOf(ExecutionException.class)
                    .hasCauseInstanceOf(DeadlineExceededException.class)
                    .satisfies(t -> assertThat(((DeadlineExceededException) t.getCause()).deadlineMs())
                            .as("inner로 흘러간 deadline은 caller 옵션값(50)이지 default(5000)가 아니다")
                            .isEqualTo(50));
        }

        @Test
        @DisplayName("default=50(짧음)이지만 caller가 deadlineMs=5000을 지정하면 50ms는 발화하지 않고 5000ms가 적용되어 정상값이 그대로 반환된다 (looser 방향)")
        void callerOptionCanRaiseAboveDefault() {
            // "옵션 우선"이 강하게도(=낮춤) 약하게도(=올림) 모두 작동함을 명세.
            // "option < default일 때만 option 우선" 같은 잘못된 분기를 막는다.
            SingleFlightCoordinator coord = new DeadlineDecorator(inner, 50);
            SingleFlightOptions looserOpts = SingleFlightOptions.builder().deadlineMs(5_000).build();

            CompletableFuture<String> result = coord.execute(
                    KEY,
                    () -> delayedFuture("v", 200),
                    looserOpts);

            try {
                assertThat(result.get(2, TimeUnit.SECONDS)).isEqualTo("v");
            } catch (Exception e) {
                throw new AssertionError("looser 옵션이 무시되어 짧은 default(50ms)가 발화한 회귀", e);
            }
        }

        @Test
        @DisplayName("default=5000인데 caller가 deadlineMs=50을 지정하면 inner에 전달되는 effective options.deadlineMs는 50이다 — 옵션 우선의 직접 검증")
        void callerOptionIsForwardedToInnerVerbatim() {
            // RecordingCoordinator로 inner에 흘러간 effective options 자체를 검사.
            // [2]의 책임("caller 옵션이 default를 항상 이긴다")이 결과 동작과 분리되어 명세된다.
            RecordingCoordinator recorder = new RecordingCoordinator();
            SingleFlightCoordinator coord = new DeadlineDecorator(recorder, 5_000);
            SingleFlightOptions tightOpts = SingleFlightOptions.builder().deadlineMs(50).build();

            coord.execute(KEY, () -> CompletableFuture.completedFuture("v"), tightOpts);

            assertThat(recorder.lastOptions).isNotNull();
            assertThat(recorder.lastOptions.deadlineMs()).hasValue(50);
        }
    }

    // ====================================================================
    // [3] 비활성 모드
    // ====================================================================
    @Nested
    @DisplayName("[3] 비활성 모드 — defaultDeadlineMs <= 0이고 옵션도 없으면 timeout을 두르지 않고 inner에 그대로 위임한다")
    class DisabledDeadline {

        @Test
        @DisplayName("default=0이면 즉시 끝나는 작업은 timeout 없이 정상값을 반환한다")
        void defaultZeroDisablesDeadline() throws Exception {
            SingleFlightCoordinator coord = new DeadlineDecorator(inner, 0);

            CompletableFuture<String> result = coord.execute(
                    KEY, () -> CompletableFuture.completedFuture("ok"));

            assertThat(result.get(1, TimeUnit.SECONDS)).isEqualTo("ok");
        }

        @Test
        @DisplayName("default가 음수여도 비활성으로 동작한다 — 가드는 > 0 기준이므로 0 이하는 모두 무제한")
        void negativeDefaultAlsoDisablesDeadline() throws Exception {
            SingleFlightCoordinator coord = new DeadlineDecorator(inner, -1);

            CompletableFuture<String> result = coord.execute(
                    KEY, () -> CompletableFuture.completedFuture("ok"));

            assertThat(result.get(1, TimeUnit.SECONDS)).isEqualTo("ok");
        }

        @Test
        @DisplayName("default=0이고 옵션도 비어 있으면 inner에 전달되는 options는 변형 없이 빈 채로 위임된다 — deadline 주입 없음")
        void disabledModePassesOptionsThroughVerbatim() {
            // [3]의 본질적 책임("disabled면 옵션을 변형하지 않는다") 직접 검증.
            // 결과 동작(timeout 미발화)과 분리해 명세한다.
            RecordingCoordinator recorder = new RecordingCoordinator();
            SingleFlightCoordinator coord = new DeadlineDecorator(recorder, 0);

            coord.execute(KEY, () -> CompletableFuture.completedFuture("v"));

            assertThat(recorder.lastOptions).isNotNull();
            assertThat(recorder.lastOptions.deadlineMs())
                    .as("disabled 분기는 caller가 안 준 deadline을 주입하지 않는다")
                    .isEmpty();
        }

        @Test
        @DisplayName("default=0(비활성)여도 caller가 명시한 options.deadlineMs는 그대로 적용된다 — 비활성 모드는 default 무효화일 뿐 caller 옵션 무시가 아니다")
        void disabledDefaultStillHonorsCallerOption() {
            // default <= 0 분기로 들어가도, options.deadlineMs가 있으면 effectiveDeadline > 0이라
            // disabled 분기에 들어가지 않는다. "disabled면 옵션도 무시" 같은 회귀를 차단.
            SingleFlightCoordinator coord = new DeadlineDecorator(inner, 0);
            SingleFlightOptions tightOpts = SingleFlightOptions.builder().deadlineMs(50).build();

            CompletableFuture<String> result = coord.execute(
                    KEY, CompletableFuture::new, tightOpts);

            assertThatThrownBy(() -> result.get(2, TimeUnit.SECONDS))
                    .isInstanceOf(ExecutionException.class)
                    .hasCauseInstanceOf(DeadlineExceededException.class);
        }
    }

    // ====================================================================
    // [4] 정상 완료 패스스루
    // ====================================================================
    @Nested
    @DisplayName("[4] 정상 완료 패스스루 — 작업이 deadline 안에 끝나면 timeout 채널은 결과를 변형하지 않는다")
    class CompletedBeforeDeadlinePassThrough {

        @Test
        @DisplayName("작업이 deadline 안에 성공하면 그 값이 그대로 호출자에게 전달된다")
        void successResultIsForwardedUnchanged() throws Exception {
            SingleFlightCoordinator coord = new DeadlineDecorator(inner, 1_000);

            CompletableFuture<String> result = coord.execute(
                    KEY, () -> CompletableFuture.completedFuture("fast"));

            assertThat(result.get(500, TimeUnit.MILLISECONDS)).isEqualTo("fast");
        }

        @Test
        @DisplayName("작업이 deadline 안에 비-Timeout 예외로 실패하면 DeadlineExceededException으로 번역되지 않고 원래 cause가 그대로 전파된다")
        void innerFailureIsNotTranslatedToDeadlineException() {
            // exceptionallyCompose 분기가 TimeoutException만 골라 번역해야 한다는 계약.
            // "모든 실패를 DeadlineExceededException으로 감싼다" 같은 잘못된 일반화를 차단.
            SingleFlightCoordinator coord = new DeadlineDecorator(inner, 1_000);
            RuntimeException downstream = new RuntimeException("downstream-broke");

            CompletableFuture<String> result = coord.execute(
                    KEY, () -> CompletableFuture.failedFuture(downstream));

            assertThatThrownBy(() -> result.get(1, TimeUnit.SECONDS))
                    .isInstanceOf(ExecutionException.class)
                    .hasCauseReference(downstream);
        }
    }

    // ====================================================================
    // [5] 위임
    // ====================================================================
    @Nested
    @DisplayName("[5] 위임 — getInflightState/forceRelease는 inner 코디네이터에 그대로 전달된다")
    class PassThrough {

        @Test
        @DisplayName("getInflightState()는 inner의 스냅샷과 동일한 결과를 반환한다")
        void getInflightStateDelegatesToInner() {
            SingleFlightCoordinator coord = new DeadlineDecorator(inner, 5_000);

            CompletableFuture<String> hanging = new CompletableFuture<>();
            coord.execute(KEY, () -> hanging);

            awaitSingleInflight();

            assertThat(coord.getInflightState()).isEqualTo(inner.getInflightState());

            hanging.complete("done");
        }

        @Test
        @DisplayName("forceRelease는 inner를 거쳐 매달린 호출자에게 ForceReleasedException을 전파한다")
        void forceReleaseDelegatesToInner() throws Exception {
            SingleFlightCoordinator coord = new DeadlineDecorator(inner, 5_000);

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
    // [6] 입력 계약
    // ====================================================================
    @Nested
    @DisplayName("[6] 입력 계약 — 생성자/실행 인자의 null을 즉시 NPE로 거부한다 (fail-fast)")
    class InputContract {

        @Test
        @DisplayName("new DeadlineDecorator(null, _) → NPE(\"inner\")")
        void constructorRejectsNullInner() {
            assertThatThrownBy(() -> new DeadlineDecorator(null, 1_000))
                    .isInstanceOf(NullPointerException.class)
                    .hasMessage("inner");
        }

        @Test
        @DisplayName("execute(\"k\", op, null) → NPE — defaultDeadlineMs 분기와 무관하게 항상 NPE")
        void executeRejectsNullOptions() {
            // defaultDeadlineMs > 0 분기와 <= 0 분기가 모두 NPE를 던져야 한다.
            // 두 경로 모두 첫 줄에서 options.deadlineMs()를 deref하므로 자연 NPE 발생.
            SingleFlightCoordinator coordEnabled = new DeadlineDecorator(inner, 5_000);
            SingleFlightCoordinator coordDisabled = new DeadlineDecorator(inner, 0);

            for (SingleFlightCoordinator coord : new SingleFlightCoordinator[]{coordEnabled, coordDisabled}) {
                assertThatThrownBy(() -> coord.execute(
                        KEY, () -> CompletableFuture.completedFuture("v"), null))
                        .isInstanceOf(NullPointerException.class);
            }
        }
    }

    // ====================================================================
    // Helpers — base inner 코디네이터 상태 동기화 / 지연 future / test double
    // ====================================================================

    private void awaitSingleInflight() {
        await().atMost(1, TimeUnit.SECONDS)
                .until(() -> inner.getInflightState().size() == 1);
    }

    /**
     * 별도 스레드에서 {@code delayMs} 후에 {@code value}로 완료되는 future를
     * 만든다. [2] 옵션 우선의 looser 방향 검증에서 "default(50ms)는 발화하지 않고
     * 옵션(5000ms) 안에 작업이 끝난다"를 deterministic하게 재현하는 용도.
     * Thread.sleep을 테스트 본문에 직접 박지 않기 위한 헬퍼.
     */
    private static <T> CompletableFuture<T> delayedFuture(T value, long delayMs) {
        CompletableFuture<T> f = new CompletableFuture<>();
        Thread t = new Thread(() -> {
            try {
                Thread.sleep(delayMs);
                f.complete(value);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                f.completeExceptionally(e);
            }
        }, "delayed-future");
        t.setDaemon(true);
        t.start();
        return f;
    }

    /**
     * Test double — inner로 전달된 effective options를 직접 관찰하기 위한 헬퍼.
     * thin decorator의 본질적 책임("options 변형 → 위임")을 결과 동작과 분리해
     * 명세할 때 사용한다. CapacityDecoratorTest의 RecordingCoordinator와 같은 형태.
     */
    private static final class RecordingCoordinator implements SingleFlightCoordinator {
        SingleFlightOptions lastOptions;
        String lastKey;

        @Override
        public <T> CompletableFuture<T> execute(
                String key,
                Supplier<CompletableFuture<T>> operation,
                SingleFlightOptions options) {
            this.lastKey = key;
            this.lastOptions = options;
            return CompletableFuture.<T>completedFuture(null);
        }

        @Override
        public List<InflightEntry> getInflightState() {
            return List.of();
        }

        @Override
        public CompletableFuture<Void> forceRelease(String key, String reason) {
            return CompletableFuture.completedFuture(null);
        }
    }
}
