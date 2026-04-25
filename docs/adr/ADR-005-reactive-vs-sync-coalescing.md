# ADR-005 — Reactive vs Sync Coalescing

| Field | Value |
|---|---|
| Status | **📅 Planned** (Phase 6 시작 시 작성) |
| Date | TBD |
| Phase | 6 |
| Deciders | @PreAgile |
| Related | [ADR-001](ADR-001-port-adapter-pattern.md), [ADR-003](ADR-003-decorator-stack-order.md) |

---

## Status: Planned

이 ADR 은 **placeholder** 입니다. Phase 6 (WebFlux Demo) 시작 시 실제 결정 + 측정으로 채워집니다.

현재까지의 hypothesis 만 outline.

---

## Planned Context

Phase 1~5 까지의 Coordinator 는 sync API (`CompletableFuture<T>`) 기반.
Phase 6 의 WebFlux 환경에선 reactive API (`Mono<T>`) 가 자연스러움.

선택지:
1. Sync API 그대로 + WebFlux 측에서 변환 (`Mono.fromFuture`)
2. Reactive 전용 별도 Port (`ReactiveSingleFlightCoordinator`)
3. 단일 Port 가 둘 다 (overload methods)

## Planned Comparison

| Approach | Pros | Cons |
|---|---|---|
| **A. Sync only + Mono.fromFuture wrap** | Port 단일, Java 표준 | Reactor backpressure 손실 |
| **B. Reactive 전용 Port 분리** | Reactor idiom 자연 (Sinks.One), backpressure 보존 | Port 2개 (ReactiveSingleFlightCoordinator) |
| **C. 단일 Port + overload** | API 통합 | 인터페이스 복잡, polymorphism issue |

## Planned Hypothesis

🎯 **H1**: WebFlux 의 backpressure 와 cancellation 을 살리려면 reactive 전용 Port 가 자연스러움.
🎯 **H2**: `Mono.cache()` 의 함정 (TTL 없음, 실패 캐시) 을 회피하려면 `Sinks.One` 기반 직접 구현 필요.
🎯 **H3**: Sync 와 reactive 의 본질은 같음 — coalesce 알고리즘 (`computeIfAbsent` style) 동일, idiom 만 다름.

## Planned Reactive Implementation Sketch

```java
public interface ReactiveSingleFlightCoordinator {
    <T> Mono<T> execute(String key, Supplier<Mono<T>> operation);
    // ... rest of port
}

public class ReactiveInProcessCoordinator implements ReactiveSingleFlightCoordinator {
    private final ConcurrentHashMap<String, Sinks.One<?>> inflight = new ConcurrentHashMap<>();

    @Override
    public <T> Mono<T> execute(String key, Supplier<Mono<T>> op) {
        @SuppressWarnings("unchecked")
        Sinks.One<T> sink = (Sinks.One<T>) inflight.computeIfAbsent(key, k -> {
            Sinks.One<T> s = Sinks.one();
            op.get().subscribe(
                v -> { s.tryEmitValue(v); inflight.remove(k); },
                e -> { s.tryEmitError(e); inflight.remove(k); }
            );
            return s;
        });
        return sink.asMono();
    }
}
```

→ Sync 의 `CompletableFuture.whenComplete` 와 reactive 의 `subscribe + tryEmit` 가 isomorphic.

## Planned Pitfalls (Reactive 특화)

🎯 **P1**: `Mono.cache()` 의 함정
- TTL 없음 (메모리 leak)
- 실패 시에도 cache (다음 요청도 같은 실패 받음)
- → 직접 `Sinks.One` 으로 풀어야 함

🎯 **P2**: Hot vs Cold publisher
- `Sinks.One` 은 hot (외부 trigger 로 emit)
- 다중 subscribe 시 동일 결과 broadcast
- Cold 처럼 동작 안 함 → backpressure 의미 다름

🎯 **P3**: Cancellation propagation
- WebFlux 의 client cancel 시 owner 도 cancel? → 정책 결정 필요
- 1 caller cancel 해도 다른 waiter 가 있으면 owner 진행 (single-flight 본질)

## Planned Cross-paradigm Comparison

[cross-paradigm/mvc-vs-webflux.md](../cross-paradigm/mvc-vs-webflux.md) 와 연동.

| Idiom | MVC (Java sync) | WebFlux (Reactor) |
|---|---|---|
| Result holder | `CompletableFuture<T>` | `Sinks.One<T>` |
| Cleanup | `whenComplete` | `subscribe(_, _)` 의 둘째 callback |
| Timeout | `orTimeout` | `Mono.timeout` |
| Map operation | `computeIfAbsent` | 같음 (둘 다 ConcurrentHashMap) |

→ **본질 동일, idiom 만 다름.** [THESIS.md](../THESIS.md) 의 L3 시연.

## Planned Validation

Phase 6 측정으로:
- 🎯 같은 시나리오 (100 VU, 500ms downstream) 에서 sync vs reactive 의 외부 호출 수 비교
- 🎯 backpressure 시나리오 (sustained > processing rate) 에서 reactive 가 sync 대비 어떤 추가 가치

## To Be Filled

- [ ] Phase 6 시작 시 Status: Proposed
- [ ] Concrete decision (Approach A vs B vs C)
- [ ] Sinks.One 구현 detail + edge case 처리
- [ ] 측정 비교 결과
- [ ] Cross-paradigm chapter 와 cross-link
- [ ] Phase 6 완료 시 Status: Accepted

## References (Planned)

- Reactor docs: https://projectreactor.io/docs/core/release/reference/
- `Sinks.One`: https://projectreactor.io/docs/core/release/api/reactor/core/publisher/Sinks.html
- Backpressure 개념: https://projectreactor.io/docs/core/release/reference/#reactive-streams
- ADR-001 (Port/Adapter — 이 ADR 의 전제)
