package com.portfolio.singleflight.coordinator.decorator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import com.portfolio.singleflight.coordinator.InflightEntry;
import com.portfolio.singleflight.coordinator.SingleFlightCoordinator;
import com.portfolio.singleflight.coordinator.SingleFlightOptions;
import com.portfolio.singleflight.coordinator.adapter.InProcessSingleFlightCoordinator;
import com.portfolio.singleflight.coordinator.exception.CongestionException;
import com.portfolio.singleflight.coordinator.exception.ForceReleasedException;

/**
 * {@link CapacityDecorator}의 행동 계약(behavioral contract).
 *
 * <p>이 데코레이터는 thin policy injector이다. caller의 {@link SingleFlightOptions}에
 * {@code maxWaiters}가 비어 있을 때 자기가 보유한 기본값을 주입한 뒤 inner
 * 코디네이터에 위임한다. 실제 cap 검사·증가는 base 어댑터의 {@code compute}
 * 안에서 원자적으로 일어난다(ADR-001 §"Capacity nuance"). 본 테스트는 위 두
 * 책임의 경계를 실행 가능한 명세로 기술한다.
 *
 * <h2>변경자에게 — 이 테스트는 계약이다</h2>
 * <p>이 클래스(또는 의존 클래스)를 수정하는 사람은 모든 테스트가 초록색인 상태로
 * PR을 올려야 한다. 하나라도 빨간색이면 데코레이터가 약속한 정책 boundary 중
 * 하나가 깨진 것이다. 의도된 계약 변경이라면 이 테스트도 함께 갱신하고 PR
 * 설명에 변경 사유를 남긴다.
 *
 * <h2>이 데코레이터가 보장하는 7가지 속성</h2>
 * <ol>
 *   <li><b>기본 cap 주입:</b> {@code defaultMaxWaiters > 0}이고 caller가
 *       options에 {@code maxWaiters}를 지정하지 않으면 기본값을 주입해 base에
 *       위임한다.</li>
 *   <li><b>옵션 우선:</b> caller가 명시한 {@code options.maxWaiters}는 항상
 *       데코레이터의 기본값을 덮어쓴다 (강하게도 약하게도).</li>
 *   <li><b>비활성 모드:</b> {@code defaultMaxWaiters <= 0}이고 옵션도 없으면
 *       cap을 주입하지 않으며 결과적으로 무제한으로 통과한다.</li>
 *   <li><b>거부의 일관성:</b> cap 거부는 base의 원자적 검사를 그대로 통과해
 *       오며, 거부된 attach가 {@code waiterCount}를 변경하지 않는다. 또한 cap
 *       거부 후 owner가 끝나면 후속 호출은 새 owner로 정상 진입한다.</li>
 *   <li><b>키 격리:</b> cap은 키 단위로 적용되며, 한 키가 가득 차도 다른 키는
 *       영향받지 않는다.</li>
 *   <li><b>위임:</b> {@code getInflightState}와 {@code forceRelease}는 inner
 *       코디네이터에 그대로 전달된다.</li>
 *   <li><b>입력 계약:</b> 생성자의 {@code inner} 인자가 null이면 NPE로 즉시
 *       거부된다 (fail-fast).</li>
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
@DisplayName("CapacityDecorator — caller가 옵션을 지정하지 않을 때 기본 maxWaiters를 주입하는 thin 데코레이터")
class CapacityDecoratorTest {

    private static final String KEY = "k";

    private InProcessSingleFlightCoordinator inner;

    @BeforeEach
    void setUp() {
        inner = new InProcessSingleFlightCoordinator();
    }

    // ====================================================================
    // [1] 기본 cap 주입
    // ====================================================================
    @Nested
    @DisplayName("[1] 기본 cap 주입 — defaultMaxWaiters > 0이고 caller가 옵션을 지정하지 않으면 기본값을 적용한다")
    class DefaultCapInjection {

        @Test
        @DisplayName("default=2이면 owner+waiter 2명에서 cap에 도달하고 3번째 attach는 CongestionException으로 거부된다")
        void defaultCapAppliedWhenNoCallerOption() {
            SingleFlightCoordinator coord = new CapacityDecorator(inner, 2);

            CompletableFuture<String> hanging = new CompletableFuture<>();
            coord.execute(KEY, () -> hanging);
            coord.execute(KEY, () -> hanging);

            awaitSingleInflightWithWaiterCount(2);

            assertThatThrownBy(() -> coord.execute(KEY, () -> hanging))
                    .isInstanceOf(CongestionException.class);

            hanging.complete("done");
        }

        @Test
        @DisplayName("default=2이고 caller가 옵션 미지정이면 inner에 전달되는 effective options.maxWaiters는 2다 — option injection 직접 검증")
        void defaultCapIsInjectedIntoEffectiveOptions() {
            // 통합 검증(거부 발생 → 추론)이 아니라 inner로 흘러가는 옵션 자체를 확인.
            // 데코레이터의 본질적 책임("default 값을 effective options에 주입")이
            // 결과 동작과 분리되어 명세된다.
            RecordingCoordinator recorder = new RecordingCoordinator();
            SingleFlightCoordinator coord = new CapacityDecorator(recorder, 2);

            coord.execute(KEY, () -> CompletableFuture.completedFuture("v"));

            assertThat(recorder.lastOptions).isNotNull();
            assertThat(recorder.lastOptions.maxWaiters()).hasValue(2);
        }
    }

    // ====================================================================
    // [2] 옵션 우선
    // ====================================================================
    @Nested
    @DisplayName("[2] 옵션 우선 — caller가 명시한 options.maxWaiters는 항상 데코레이터 기본값을 덮어쓴다")
    class OptionOverride {

        @Test
        @DisplayName("default=100이지만 caller가 maxWaiters=1로 지정하면 cap은 1로 적용되어 owner 직후 거부된다 (strict 방향)")
        void callerOptionCanLowerBelowDefault() {
            SingleFlightCoordinator coord = new CapacityDecorator(inner, 100);
            SingleFlightOptions tightOpts = SingleFlightOptions.builder().maxWaiters(1).build();

            CompletableFuture<String> hanging = new CompletableFuture<>();
            coord.execute(KEY, () -> hanging, tightOpts);

            awaitSingleInflight();

            assertThatThrownBy(() -> coord.execute(KEY, () -> hanging, tightOpts))
                    .isInstanceOf(CongestionException.class);

            hanging.complete("done");
        }

        @Test
        @DisplayName("default=1이지만 caller가 maxWaiters=3을 지정하면 cap은 3으로 올라가 owner+waiter 2명까지 매달리고 4번째에서 거부된다 (looser 방향)")
        void callerOptionCanRaiseAboveDefault() {
            // "옵션 우선"이 강하게도(=낮춤) 약하게도(=올림) 모두 작동함을 명세.
            // "option < default일 때만 option 우선" 같은 잘못된 분기를 막는다.
            SingleFlightCoordinator coord = new CapacityDecorator(inner, 1);
            SingleFlightOptions looserOpts = SingleFlightOptions.builder().maxWaiters(3).build();

            CompletableFuture<String> hanging = new CompletableFuture<>();
            coord.execute(KEY, () -> hanging, looserOpts);
            coord.execute(KEY, () -> hanging, looserOpts);
            coord.execute(KEY, () -> hanging, looserOpts);

            awaitSingleInflightWithWaiterCount(3);

            assertThatThrownBy(() -> coord.execute(KEY, () -> hanging, looserOpts))
                    .isInstanceOf(CongestionException.class);

            hanging.complete("done");
        }
    }

    // ====================================================================
    // [3] 비활성 모드
    // ====================================================================
    @Nested
    @DisplayName("[3] 비활성 모드 — defaultMaxWaiters <= 0이고 옵션도 없으면 cap 없이 통과시킨다")
    class DisabledCap {

        @Test
        @DisplayName("default=0이면 100명이 매달려도 거부되지 않는다")
        void defaultZeroDisablesCap() {
            SingleFlightCoordinator coord = new CapacityDecorator(inner, 0);

            CompletableFuture<String> hanging = new CompletableFuture<>();
            for (int i = 0; i < 100; i++) {
                coord.execute(KEY, () -> hanging);
            }

            awaitSingleInflightWithWaiterCount(100);

            hanging.complete("done");
        }

        @Test
        @DisplayName("default가 음수여도 비활성으로 동작한다 — 가드는 > 0 기준이므로 0 이하는 모두 무제한")
        void negativeDefaultAlsoDisablesCap() {
            SingleFlightCoordinator coord = new CapacityDecorator(inner, -1);

            CompletableFuture<String> hanging = new CompletableFuture<>();
            for (int i = 0; i < 50; i++) {
                coord.execute(KEY, () -> hanging);
            }

            awaitSingleInflightWithWaiterCount(50);

            hanging.complete("done");
        }

        @Test
        @DisplayName("default=0(비활성)여도 caller가 명시한 options.maxWaiters는 그대로 적용된다 — 비활성 모드는 default 무효화일 뿐 caller 옵션 무시가 아니다")
        void disabledDefaultStillHonorsCallerOption() {
            // default <= 0 분기는 options를 그대로 inner에 전달한다.
            // "disabled면 옵션도 무시" 같은 잘못된 회귀를 차단.
            SingleFlightCoordinator coord = new CapacityDecorator(inner, 0);
            SingleFlightOptions tightOpts = SingleFlightOptions.builder().maxWaiters(1).build();

            CompletableFuture<String> hanging = new CompletableFuture<>();
            coord.execute(KEY, () -> hanging, tightOpts);

            awaitSingleInflight();

            assertThatThrownBy(() -> coord.execute(KEY, () -> hanging, tightOpts))
                    .isInstanceOf(CongestionException.class);

            hanging.complete("done");
        }
    }

    // ====================================================================
    // [4] 거부의 일관성
    // ====================================================================
    @Nested
    @DisplayName("[4] 거부의 일관성 — base의 원자적 거부를 그대로 통과시키고, 거부 후에도 후속 호출은 정상 진입한다")
    class RejectionConsistency {

        @Test
        @DisplayName("거부된 attach는 waiterCount를 변경하지 않는다 — 5번 연속 거부해도 카운트 그대로")
        void rejectionDoesNotMutateWaiterCount() {
            SingleFlightCoordinator coord = new CapacityDecorator(inner, 2);

            CompletableFuture<String> hanging = new CompletableFuture<>();
            coord.execute(KEY, () -> hanging);
            coord.execute(KEY, () -> hanging);

            awaitSingleInflightWithWaiterCount(2);

            for (int i = 0; i < 5; i++) {
                assertThatThrownBy(() -> coord.execute(KEY, () -> hanging))
                        .isInstanceOf(CongestionException.class);
                assertThat(inner.getInflightState().get(0).waiterCount()).isEqualTo(2);
            }

            hanging.complete("done");
        }

        @Test
        @DisplayName("앞 작업이 끝난 뒤 들어온 호출은 cap 안에서 정상적으로 새 owner가 된다")
        void newOwnerCanStartAfterPreviousCompletes() throws Exception {
            SingleFlightCoordinator coord = new CapacityDecorator(inner, 2);

            CompletableFuture<String> r1 = coord.execute(KEY,
                    () -> CompletableFuture.completedFuture("v1"));
            assertThat(r1.get(1, TimeUnit.SECONDS)).isEqualTo("v1");

            CompletableFuture<String> r2 = coord.execute(KEY,
                    () -> CompletableFuture.completedFuture("v2"));
            assertThat(r2.get(1, TimeUnit.SECONDS)).isEqualTo("v2");
        }
    }

    // ====================================================================
    // [5] 키 격리
    // ====================================================================
    @Nested
    @DisplayName("[5] 키 격리 — 한 키의 cap이 다른 키의 attach에 영향을 주지 않는다")
    class PerKeyIsolation {

        @Test
        @DisplayName("키 A가 cap에 도달해 거부 상태여도 키 B는 독립적으로 attach 가능하다")
        void capAppliesPerKeyIndependently() {
            SingleFlightCoordinator coord = new CapacityDecorator(inner, 2);

            CompletableFuture<String> hangingA = new CompletableFuture<>();
            CompletableFuture<String> hangingB = new CompletableFuture<>();

            coord.execute("A", () -> hangingA);
            coord.execute("A", () -> hangingA); // A는 cap=2 도달

            coord.execute("B", () -> hangingB); // B는 owner만

            await().atMost(1, TimeUnit.SECONDS)
                    .until(() -> inner.getInflightState().size() == 2);

            assertThatThrownBy(() -> coord.execute("A", () -> hangingA))
                    .isInstanceOf(CongestionException.class);

            // B는 cap 미달이라 추가 attach 가능 — 키 A의 거부와 무관
            coord.execute("B", () -> hangingB);

            int waitersForB = inner.getInflightState().stream()
                    .filter(e -> e.key().equals("B"))
                    .mapToInt(InflightEntry::waiterCount)
                    .sum();
            assertThat(waitersForB).isEqualTo(2);

            hangingA.complete("a");
            hangingB.complete("b");
        }
    }

    // ====================================================================
    // [6] 위임 (passthrough)
    // ====================================================================
    @Nested
    @DisplayName("[6] 위임 — getInflightState/forceRelease는 inner 코디네이터에 그대로 전달된다")
    class PassThrough {

        @Test
        @DisplayName("getInflightState()는 inner의 스냅샷과 동일한 결과를 반환한다")
        void getInflightStateDelegatesToInner() {
            SingleFlightCoordinator coord = new CapacityDecorator(inner, 5);

            CompletableFuture<String> hanging = new CompletableFuture<>();
            coord.execute(KEY, () -> hanging);

            awaitSingleInflight();

            assertThat(coord.getInflightState()).isEqualTo(inner.getInflightState());

            hanging.complete("done");
        }

        @Test
        @DisplayName("forceRelease는 inner를 거쳐 매달린 호출자에게 ForceReleasedException을 전파한다")
        void forceReleaseDelegatesToInner() throws Exception {
            SingleFlightCoordinator coord = new CapacityDecorator(inner, 5);

            CompletableFuture<String> hanging = new CompletableFuture<>();
            CompletableFuture<String> r = coord.execute(KEY, () -> hanging);

            awaitSingleInflight();

            coord.forceRelease(KEY, "operator-test").get(1, TimeUnit.SECONDS);

            assertThatThrownBy(() -> r.get(1, TimeUnit.SECONDS))
                    .isInstanceOf(ExecutionException.class)
                    .hasCauseInstanceOf(ForceReleasedException.class);
            assertThat(inner.getInflightState()).isEmpty();
        }
    }

    // ====================================================================
    // [7] 입력 계약
    // ====================================================================
    @Nested
    @DisplayName("[7] 입력 계약 — 생성자/실행 인자의 null을 즉시 NPE로 거부한다 (fail-fast)")
    class InputContract {

        @Test
        @DisplayName("new CapacityDecorator(null, _) → NPE(\"inner\")")
        void constructorRejectsNullInner() {
            assertThatThrownBy(() -> new CapacityDecorator(null, 5))
                    .isInstanceOf(NullPointerException.class)
                    .hasMessage("inner");
        }

        @Test
        @DisplayName("execute(\"k\", op, null) → NPE(\"options\") — defaultMaxWaiters 분기와 무관하게 일관된 메시지")
        void executeRejectsNullOptions() {
            // defaultMaxWaiters > 0 분기와 <= 0 분기가 동일한 NPE("options")를 던져야 한다.
            // 분기에 따라 JVM helpful-NPE vs requireNonNull 메시지가 갈리던 비일관성을 차단.
            SingleFlightCoordinator coordEnabled = new CapacityDecorator(inner, 5);
            SingleFlightCoordinator coordDisabled = new CapacityDecorator(inner, 0);

            for (SingleFlightCoordinator coord : new SingleFlightCoordinator[]{coordEnabled, coordDisabled}) {
                assertThatThrownBy(() -> coord.execute(
                        KEY, () -> CompletableFuture.completedFuture("v"), null))
                        .isInstanceOf(NullPointerException.class)
                        .hasMessage("options");
            }
        }
    }

    // ====================================================================
    // Helpers — base inner 코디네이터 상태 동기화
    // ====================================================================

    private void awaitSingleInflight() {
        await().atMost(1, TimeUnit.SECONDS)
                .until(() -> inner.getInflightState().size() == 1);
    }

    private void awaitSingleInflightWithWaiterCount(int waiterCount) {
        await().atMost(1, TimeUnit.SECONDS)
                .until(() -> {
                    var state = inner.getInflightState();
                    return state.size() == 1 && state.get(0).waiterCount() == waiterCount;
                });
    }

    /**
     * Test double — inner로 전달된 effective options를 직접 관찰하기 위한 헬퍼.
     * thin decorator의 본질적 책임("options 변형 → 위임")을 결과 동작과 분리해
     * 명세할 때 사용한다.
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
