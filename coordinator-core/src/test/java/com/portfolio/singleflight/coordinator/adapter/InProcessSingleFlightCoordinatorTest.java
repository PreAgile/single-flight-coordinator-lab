package com.portfolio.singleflight.coordinator.adapter;

import com.portfolio.singleflight.coordinator.SingleFlightCoordinator;
import com.portfolio.singleflight.coordinator.SingleFlightOptions;
import com.portfolio.singleflight.coordinator.exception.CongestionException;
import com.portfolio.singleflight.coordinator.exception.ForceReleasedException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
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
 * {@link InProcessSingleFlightCoordinator}의 행동 계약(behavioral contract).
 *
 * <p>이 테스트 클래스는 코디네이터가 약속한 행동을 <b>실행 가능한 명세서</b>로
 * 기술한다. 각 {@code @Nested} 그룹은 코디네이터가 외부에 보장하는 한 가지 속성에
 * 대응한다. 위에서 아래로 읽으면 코디네이터의 행동이 한 편의 이야기처럼 이어진다.
 *
 * <h2>변경자에게 — 이 테스트는 계약이다</h2>
 * <p>이 클래스(또는 의존 클래스)를 수정하는 사람은 모든 테스트가 초록색인 상태로
 * PR을 올려야 한다. 하나라도 빨간색이면 SingleFlight 패턴이 사용자에게 한 약속
 * 중 하나가 깨진 것이다. 의도된 계약 변경이라면 이 테스트도 함께 갱신하고
 * PR 설명에 변경 사유를 남긴다.
 *
 * <h2>코디네이터가 보장하는 8가지 속성</h2>
 * <ol>
 *   <li><b>코알레싱:</b> 같은 키로 동시에 들어온 N명의 호출자에 대해 작업
 *       람다는 1번만 실행되며, N명 모두 같은 결과를 받는다.</li>
 *   <li><b>결과 일관성:</b> 작업의 성공값/실패원인은 매달린 모두에게 동일하게
 *       전파된다 (같은 cause 객체 공유).</li>
 *   <li><b>자원 정리:</b> 작업이 정상/예외/동기 throw 어느 경로로 끝나든
 *       inflight 맵에서 즉시 제거된다 — 누수 없음.</li>
 *   <li><b>kill switch:</b> 운영자가 {@code forceRelease}로 매달린 작업을
 *       강제 종료할 수 있고, 이때 매달린 모두는 {@link ForceReleasedException}으로
 *       실패한다.</li>
 *   <li><b>용량 제한:</b> {@code maxWaiters}를 넘는 attach는
 *       {@link CongestionException}으로 <b>원자적으로</b> 거부되며 기존
 *       waiter의 카운트는 변하지 않는다.</li>
 *   <li><b>호출자 격리:</b> 한 호출자가 자기 future를 cancel해도 작업이나
 *       다른 호출자에게 전파되지 않는다.</li>
 *   <li><b>입력 계약:</b> {@code null} 인자는 메서드 진입 즉시 NPE로 거부된다
 *       (fail-fast).</li>
 *   <li><b>관측성:</b> {@code getInflightState()}는 진행 중인 작업의
 *       key/시작시각/매달린 수/hostId를 스냅샷으로 노출한다.</li>
 * </ol>
 *
 * <h2>그룹별 단일 관심사 원칙</h2>
 * <p>각 {@code @Nested} 그룹의 테스트는 자기 그룹의 보장 속성 <b>하나</b>에만
 * 집중한다. 자원 정리(cleanup) 같은 횡단 속성은 <b>[3] 자원 정리</b> 그룹에서
 * 전담 검증하고, 다른 그룹의 테스트가 우연히 inflight 맵을 다룬다는 이유로
 * cleanup 단언을 추가로 끼워 넣지 않는다 — 예: [5] 용량 제한이나
 * [8] 관측성 테스트는 {@code hangingFuture.complete("done")}으로 매달린
 * future들을 풀어주기만 하고 후속 cleanup은 단언하지 않는다. 이건
 * {@code @Nested} 그룹화의 의의 — "테스트가 깨지면 어느 보장이 깨졌는지
 * 그룹명에 즉시 보인다" — 를 유지하기 위함이다.
 *
 * <p>예외: 비동기 race가 본질적인 시나리오([1] 동시 호출, [2] 실패 전파,
 * [6] 호출자 격리)는 그 시나리오의 끝을 안전하게 기다리기 위해
 * {@link #awaitNoInflight()}를 사용한다. 이건 cleanup 회귀 탐지가 아니라
 * <b>비동기 시나리오 종료 동기화</b>이며, [3] 자원 정리 그룹의 직접
 * cleanup 단언과 역할이 다르다.
 */
@DisplayName("InProcessSingleFlightCoordinator — 같은 키 동시 호출을 1번의 실행으로 합치는 in-process 코디네이터")
class InProcessSingleFlightCoordinatorTest {

    private static final String KEY = "k";

    private SingleFlightCoordinator coordinator;

    @BeforeEach
    void setUp() {
        coordinator = new InProcessSingleFlightCoordinator();
    }

    // ====================================================================
    // [1] 코알레싱 — 핵심 보장
    // ====================================================================
    @Nested
    @DisplayName("[1] 코알레싱 — 같은 키 동시 호출자는 1번의 실행을 공유한다")
    class Coalescing {

        @Test
        @DisplayName("호출 1건이면 owner의 결과를 그대로 받고 inflight 맵이 정리된다")
        void singleCallReturnsOwnerValue() throws Exception {
            CompletableFuture<String> result = coordinator.execute(
                    KEY, () -> CompletableFuture.completedFuture("hello"));

            assertThat(result.get(1, TimeUnit.SECONDS)).isEqualTo("hello");
            assertThat(coordinator.getInflightState()).isEmpty();
        }

        @Test
        @DisplayName("동시 20건이면 작업 람다는 단 1번 실행되고 20명 모두 같은 값을 받는다")
        void concurrentCallsCoalesceIntoSingleInvocation() throws Exception {
            AtomicInteger invocations = new AtomicInteger();
            CompletableFuture<String> ownerFuture = new CompletableFuture<>();

            ExecutorService executor = Executors.newFixedThreadPool(20);
            List<CompletableFuture<String>> results = new ArrayList<>();
            CountDownLatch ready = new CountDownLatch(20);

            try {
                for (int i = 0; i < 20; i++) {
                    results.add(CompletableFuture.supplyAsync(() -> {
                        ready.countDown();
                        return coordinator.execute(KEY, () -> {
                            invocations.incrementAndGet();
                            return ownerFuture;
                        });
                    }, executor).thenCompose(f -> f));
                }
                ready.await();
                awaitSingleInflightWithWaiterCount(20);

                ownerFuture.complete("shared-result");

                for (CompletableFuture<String> r : results) {
                    assertThat(r.get(2, TimeUnit.SECONDS)).isEqualTo("shared-result");
                }
                assertThat(invocations.get()).isEqualTo(1);
            } finally {
                executor.shutdownNow();
            }

            awaitNoInflight();
        }

        @Test
        @DisplayName("fast-completion supplier에서 동시 호출도 결과 일관성과 누수 없는 정리를 보장한다 — race window는 의도된 시멘틱")
        void fastCompletionConcurrentCallsBehaveCorrectly() throws Exception {
            // supplier가 이미 완료된 future를 돌려주는 경우(캐시 히트, 동기 결정 등)에도
            // 동시 호출 시나리오가 깨지지 않음을 명세한다. record 삽입과 whenComplete
            // 등록 사이의 race window는 SingleFlight 패턴의 의도된 동작 — 그 윈도우에
            // 들어온 호출자는 직전 owner와 coalesce되어 같은 결과를 받고, 윈도우를
            // 벗어난 호출자는 새 owner로 fresh하게 시작한다. 둘 다 정상.
            //
            // 이 테스트는 미래의 누군가가 race window를 "버그"로 오인해
            // existing.future.isDone() 검사로 강제 new-owner 로직을 도입하면
            // (= fast-completion thundering herd 재현) 깨지도록 설계됐다.
            AtomicInteger invocations = new AtomicInteger();
            int callerCount = 50;

            ExecutorService executor = Executors.newFixedThreadPool(callerCount);
            CountDownLatch ready = new CountDownLatch(callerCount);
            List<CompletableFuture<String>> results = new ArrayList<>();

            try {
                for (int i = 0; i < callerCount; i++) {
                    results.add(CompletableFuture.supplyAsync(() -> {
                        ready.countDown();
                        return coordinator.execute(KEY, () -> {
                            invocations.incrementAndGet();
                            return CompletableFuture.completedFuture("v");
                        });
                    }, executor).thenCompose(f -> f));
                }
                ready.await();

                for (CompletableFuture<String> r : results) {
                    assertThat(r.get(2, TimeUnit.SECONDS)).isEqualTo("v");
                }

                assertThat(invocations.get())
                        .as("supplier 호출 수는 1 이상 호출자 수 이하 — 정확한 값은 race window 타이밍이 결정")
                        .isBetween(1, callerCount);
            } finally {
                executor.shutdownNow();
            }

            awaitNoInflight();
        }

        @Test
        @DisplayName("앞 작업이 끝난 뒤 들어온 호출은 새 owner로 재실행된다 — 시간이 안 겹치면 코알레싱 없음")
        void sequentialCallsRestartOwnerEachTime() throws Exception {
            AtomicInteger invocations = new AtomicInteger();

            for (int i = 0; i < 5; i++) {
                String result = coordinator.execute(KEY, () -> {
                    invocations.incrementAndGet();
                    return CompletableFuture.completedFuture("v");
                }).get(1, TimeUnit.SECONDS);
                assertThat(result).isEqualTo("v");
            }

            assertThat(invocations.get()).isEqualTo(5);
        }

        @Test
        @DisplayName("서로 다른 키는 독립적으로 실행되며 간섭하지 않는다")
        void differentKeysExecuteIndependently() throws Exception {
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
    }

    // ====================================================================
    // [2] 결과 일관성 — 성공이든 실패든 모두 같은 결과
    // ====================================================================
    @Nested
    @DisplayName("[2] 결과 일관성 — owner의 성공/실패는 매달린 모두에게 동일하게 전파된다")
    class ResultPropagation {

        @Test
        @DisplayName("owner가 예외로 실패하면 모든 waiter가 같은 cause 객체를 받는다")
        void ownerFailurePropagatesSameCauseToAllWaiters() {
            CompletableFuture<String> ownerFuture = new CompletableFuture<>();
            RuntimeException cause = new RuntimeException("downstream-broke");

            CompletableFuture<String> r1 = coordinator.execute(KEY, () -> ownerFuture);
            CompletableFuture<String> r2 = coordinator.execute(KEY, () -> ownerFuture);
            CompletableFuture<String> r3 = coordinator.execute(KEY, () -> ownerFuture);

            ownerFuture.completeExceptionally(cause);

            for (CompletableFuture<String> r : List.of(r1, r2, r3)) {
                assertThatThrownBy(() -> r.get(1, TimeUnit.SECONDS))
                        .isInstanceOf(ExecutionException.class)
                        .hasCauseReference(cause);
            }
            awaitNoInflight();
        }
    }

    // ====================================================================
    // [3] 자원 정리 — 어떤 경로로 끝나도 누수 없음
    // ====================================================================
    @Nested
    @DisplayName("[3] 자원 정리 — 정상/예외/비정상 종료 어느 경로든 inflight 맵에 흔적을 남기지 않는다")
    class ResourceCleanup {

        @Test
        @DisplayName("supplier가 동기로 throw하면 future는 실패 상태가 되고 맵이 즉시 정리된다")
        void operationSupplierThrowingSynchronously() {
            RuntimeException synchronousError = new RuntimeException("boom-sync");

            CompletableFuture<String> result = coordinator.execute(KEY, () -> {
                throw synchronousError;
            });

            assertThat(result).isCompletedExceptionally();
            assertThatThrownBy(result::get)
                    .isInstanceOf(ExecutionException.class)
                    .hasCauseReference(synchronousError);
            assertThat(coordinator.getInflightState()).isEmpty();
        }

        @Test
        @DisplayName("supplier가 null future를 반환하면 IllegalStateException으로 실패하고 맵이 정리된다")
        void operationSupplierReturningNullFuture() {
            CompletableFuture<String> result = coordinator.execute(KEY, () -> null);

            assertThat(result).isCompletedExceptionally();
            assertThatThrownBy(result::get)
                    .isInstanceOf(ExecutionException.class)
                    .hasCauseInstanceOf(IllegalStateException.class)
                    .hasRootCauseMessage("operation supplier returned null");
            assertThat(coordinator.getInflightState()).isEmpty();
        }
    }

    // ====================================================================
    // [4] kill switch — 운영자 강제 해제
    // ====================================================================
    @Nested
    @DisplayName("[4] kill switch — forceRelease로 영원히 안 끝나는 작업을 강제 종료할 수 있다")
    class ForceRelease {

        @Test
        @DisplayName("forceRelease는 매달린 모두에게 ForceReleasedException을 전파하고 맵에서 제거한다")
        void releasesAllWaitersAndRemovesEntry() throws Exception {
            CompletableFuture<String> hangingFuture = new CompletableFuture<>();

            CompletableFuture<String> r1 = coordinator.execute(KEY, () -> hangingFuture);
            CompletableFuture<String> r2 = coordinator.execute(KEY, () -> hangingFuture);

            await().atMost(1, TimeUnit.SECONDS)
                    .until(() -> coordinator.getInflightState().size() == 1);

            coordinator.forceRelease(KEY, "operator-test").get(1, TimeUnit.SECONDS);

            for (CompletableFuture<String> r : List.of(r1, r2)) {
                assertThatThrownBy(() -> r.get(1, TimeUnit.SECONDS))
                        .isInstanceOf(ExecutionException.class)
                        .hasCauseInstanceOf(ForceReleasedException.class);
            }
            assertThat(coordinator.getInflightState()).isEmpty();
        }

        @Test
        @DisplayName("존재하지 않는 키에 forceRelease를 호출해도 조용히 no-op으로 끝난다")
        void onAbsentKeyIsSilentNoop() throws Exception {
            CompletableFuture<Void> ack = coordinator.forceRelease("never-existed", "operator-test");

            assertThat(ack.get(1, TimeUnit.SECONDS)).isNull();
            assertThat(coordinator.getInflightState()).isEmpty();
        }
    }

    // ====================================================================
    // [5] 용량 제한 — 원자적 거부
    // ====================================================================
    @Nested
    @DisplayName("[5] 용량 제한 — maxWaiters 초과 attach는 원자적으로 거부되고 기존 카운트는 변하지 않는다")
    class Capacity {

        @Test
        @DisplayName("cap에 도달하면 다음 attach는 CongestionException으로 거부되며 waiterCount는 그대로다")
        void rejectionIsAtomicAndDoesNotMutateCount() throws Exception {
            CompletableFuture<String> hangingFuture = new CompletableFuture<>();
            SingleFlightOptions opts = SingleFlightOptions.builder().maxWaiters(3).build();

            CompletableFuture<String> owner = coordinator.execute(KEY, () -> hangingFuture, opts);
            CompletableFuture<String> w1 = coordinator.execute(KEY, () -> hangingFuture, opts);
            CompletableFuture<String> w2 = coordinator.execute(KEY, () -> hangingFuture, opts);

            awaitSingleInflightWithWaiterCount(3);

            assertThatThrownBy(() -> coordinator.execute(KEY, () -> hangingFuture, opts))
                    .isInstanceOf(CongestionException.class)
                    .extracting(t -> ((CongestionException) t).currentWaiters()).isEqualTo(3);

            assertThat(coordinator.getInflightState().getFirst().waiterCount())
                    .as("rejected attach must not mutate waiterCount")
                    .isEqualTo(3);

            hangingFuture.complete("done");
            for (var f : List.of(owner, w1, w2)) {
                assertThat(f.get(1, TimeUnit.SECONDS)).isEqualTo("done");
            }
        }

        @Test
        @DisplayName("CongestionException은 key/currentWaiters/maxWaiters/telemetryTag를 모두 노출한다")
        void exceptionExposesAllDiagnosticFields() {
            CompletableFuture<String> hangingFuture = new CompletableFuture<>();
            SingleFlightOptions opts = SingleFlightOptions.builder()
                    .maxWaiters(2)
                    .telemetryTag("naver-search")
                    .build();

            coordinator.execute(KEY, () -> hangingFuture, opts);
            coordinator.execute(KEY, () -> hangingFuture, opts);

            assertThatThrownBy(() -> coordinator.execute(KEY, () -> hangingFuture, opts))
                    .isInstanceOf(CongestionException.class)
                    .satisfies(t -> {
                        CongestionException ex = (CongestionException) t;
                        assertThat(ex.key()).isEqualTo(KEY);
                        assertThat(ex.currentWaiters()).isEqualTo(2);
                        assertThat(ex.maxWaiters()).isEqualTo(2);
                        assertThat(ex.telemetryTag()).isEqualTo("naver-search");
                    });

            hangingFuture.complete("done");
        }
    }

    // ====================================================================
    // [6] 호출자 격리 — cancel 전파 차단
    // ====================================================================
    @Nested
    @DisplayName("[6] 호출자 격리 — 한 호출자가 자기 future를 cancel해도 작업과 다른 호출자는 영향받지 않는다")
    class CallerIsolation {

        @Test
        @DisplayName("호출자 한 명의 cancel은 작업과 다른 호출자에게 전파되지 않는다")
        void cancellingOneCallersFutureDoesNotAffectOthers() throws Exception {
            CompletableFuture<String> ownerFuture = new CompletableFuture<>();

            CompletableFuture<String> r1 = coordinator.execute(KEY, () -> ownerFuture);
            CompletableFuture<String> r2 = coordinator.execute(KEY, () -> ownerFuture);
            CompletableFuture<String> r3 = coordinator.execute(KEY, () -> ownerFuture);

            assertThat(r1.cancel(true)).isTrue();
            assertThat(r1).isCancelled();

            assertThat(r2).isNotDone();
            assertThat(r3).isNotDone();
            assertThat(ownerFuture).isNotDone();

            ownerFuture.complete("v");

            assertThat(r2.get(1, TimeUnit.SECONDS)).isEqualTo("v");
            assertThat(r3.get(1, TimeUnit.SECONDS)).isEqualTo("v");

            awaitNoInflight();
        }
    }

    // ====================================================================
    // [7] 입력 계약 — fail-fast
    // ====================================================================
    @Nested
    @DisplayName("[7] 입력 계약 — public API는 null 인자를 진입 즉시 NPE로 거부한다 (fail-fast)")
    class InputContract {

        @Test
        @DisplayName("execute(null, op) → NPE(\"key\")")
        void executeRejectsNullKey() {
            assertThatThrownBy(() -> coordinator.execute(
                    null, () -> CompletableFuture.completedFuture("v")))
                    .isInstanceOf(NullPointerException.class)
                    .hasMessage("key");
        }

        @Test
        @DisplayName("execute(\"k\", null) → NPE(\"operation\")")
        void executeRejectsNullOperation() {
            assertThatThrownBy(() -> coordinator.execute(KEY, null))
                    .isInstanceOf(NullPointerException.class)
                    .hasMessage("operation");
        }

        @Test
        @DisplayName("execute(\"k\", op, null) → NPE(\"options\")")
        void executeRejectsNullOptions() {
            assertThatThrownBy(() -> coordinator.execute(
                    KEY, () -> CompletableFuture.completedFuture("v"), null))
                    .isInstanceOf(NullPointerException.class)
                    .hasMessage("options");
        }

        @Test
        @DisplayName("forceRelease(null, reason) → NPE(\"key\")")
        void forceReleaseRejectsNullKey() {
            assertThatThrownBy(() -> coordinator.forceRelease(null, "reason"))
                    .isInstanceOf(NullPointerException.class)
                    .hasMessage("key");
        }
    }

    // ====================================================================
    // [8] 관측성 — 운영 진단용 스냅샷
    // ====================================================================
    @Nested
    @DisplayName("[8] 관측성 — getInflightState()는 운영 진단에 필요한 모든 필드를 노출한다")
    class Observability {

        @Test
        @DisplayName("InflightEntry는 key, startedAt(현재 근처), waiterCount, hostId를 모두 채워서 반환한다")
        void inflightEntryExposesAllFields() {
            long before = System.currentTimeMillis();
            CompletableFuture<String> hangingFuture = new CompletableFuture<>();

            coordinator.execute("obs-key", () -> hangingFuture);
            coordinator.execute("obs-key", () -> hangingFuture);

            long after = System.currentTimeMillis();

            var state = coordinator.getInflightState();
            assertThat(state).hasSize(1);

            var entry = state.getFirst();
            assertThat(entry.key()).isEqualTo("obs-key");
            assertThat(entry.waiterCount()).isEqualTo(2);
            assertThat(entry.startedAt()).isBetween(before, after);
            assertThat(entry.hostId()).isNotBlank();

            hangingFuture.complete("done");
        }
    }

    // ====================================================================
    // Helpers — 비동기 정리/등록 동기화 (Awaitility wrapper)
    // ====================================================================

    private void awaitNoInflight() {
        await().atMost(1, TimeUnit.SECONDS)
                .until(() -> coordinator.getInflightState().isEmpty());
    }

    private void awaitSingleInflightWithWaiterCount(int waiterCount) {
        await().atMost(2, TimeUnit.SECONDS)
                .until(() -> {
                    var state = coordinator.getInflightState();
                    return state.size() == 1 && state.getFirst().waiterCount() == waiterCount;
                });
    }
}
