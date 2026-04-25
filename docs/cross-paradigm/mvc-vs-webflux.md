# MVC vs WebFlux — 같은 Thundering Herd, 다른 자원이 폭주

> ⚠️ **PLACEHOLDER** — Phase 2 (mvc-demo) 와 Phase 6 (webflux-demo) 측정 완료 시 채워짐.
> 현재는 비교 dimension 만 정의.

[THESIS.md](../THESIS.md) 의 **L3 — 다른 paradigm 비교** 단계의 핵심 챕터.

---

## Why This Comparison Matters

같은 thundering herd 패턴이 동시성 모델별로 **다른 자원으로 표출**됨. 이를 비교해서 새 framework / paradigm 만나도 같은 사고로 적응 가능함을 시연.

---

## Comparison Dimensions

비교할 7개 axis:

### 1. Concurrency Model

| | Spring MVC | Spring WebFlux |
|---|---|---|
| 패턴 | Thread-per-request | Event loop + reactive streams |
| Thread pool | Tomcat (default 200) | Reactor (CPU 코어 수) |
| Blocking 처리 | Acceptable (대기 thread 점유) | 금지 (event loop 막힘) |

### 2. Thundering Herd 표출 자원

| | Spring MVC | Spring WebFlux |
|---|---|---|
| **포화 자원** | **Tomcat thread pool** | **WebClient connection pool** |
| 보조 자원 | DB connection, 메모리 | Reactor scheduler, connection pool |
| Saturation 결과 | HTTP 503 (queue 초과) | Connection refused / backpressure overflow |

### 3. SingleFlight 효과

| 효과 | MVC | WebFlux |
|---|---|---|
| 외부 호출 N → 1 | ✓ | ✓ |
| **자원 보호 대상** | **Thread pool** | **Connection pool** |
| Latency 개선 | P99 850→32ms 추정 | P99 720→28ms 추정 |
| Throughput 개선 | 25× | 25× |

### 4. 구현 idiom 차이

#### MVC (Java sync)
```java
public CompletableFuture<Session> ensureSession(String userId) {
    return coordinator.execute(userId, () -> doEnsureSession(userId));
}

// Coordinator 내부:
ConcurrentHashMap.computeIfAbsent(key, k -> operation.get())
```

→ `CompletableFuture` 기반. `ConcurrentHashMap.computeIfAbsent` 가 atomic.

#### WebFlux (Reactor)
```java
public Mono<Session> ensureSession(String userId) {
    return reactiveCoordinator.execute(userId, () -> doEnsureSessionReactive(userId));
}

// Coordinator 내부:
ConcurrentHashMap<String, Sinks.One<T>> inflight;

return inflight.computeIfAbsent(key, k -> {
    Sinks.One<T> sink = Sinks.one();
    op.get().subscribe(
        v -> { sink.tryEmitValue(v); inflight.remove(k); },
        e -> { sink.tryEmitError(e); inflight.remove(k); }
    );
    return sink;
}).asMono();
```

→ `Sinks.One` + `Mono`. Lazy subscribe 본질, multiple waiter 가 같은 sink subscribe.

### 5. 측정 메트릭 차이

#### MVC 핵심 메트릭
- `tomcat_threads_busy{name=http-nio}` — busy thread 수 (★)
- `tomcat_threads_max{name=http-nio}` — max
- `tomcat_threads_busy_ratio` — 점유율 % (★)
- `http_server_requests_seconds{...}` — request duration

#### WebFlux 핵심 메트릭
- `reactor_netty_connection_provider_total_connections{name=...}` — connection 수 (★)
- `reactor_netty_connection_provider_active_connections{name=...}` — active
- `reactor_netty_connection_provider_pending_acquires{...}` — 대기 중 acquire
- `reactor_scheduler_queue_size{name=parallel}` — Reactor scheduler queue

### 6. Failure Mode

| Mode | MVC | WebFlux |
|---|---|---|
| Pool 포화 | HTTP 503 | Backpressure overflow |
| Slow downstream | Thread blocked, queue 누적 | Connection 점유, Mono 누적 |
| Downstream 차단 | Worker thread 점유 → 새 요청 거부 | Connection pool 고갈 |
| Recovery 시간 | Slow (queued requests timeout 후) | Fast (subscribe cancel 가능) |

### 7. Trade-offs — 어느 모델이 적합한가

| 시나리오 | MVC 권장 | WebFlux 권장 |
|---|---|---|
| Downstream 응답 빠름 (< 50ms) | ✅ 단순함이 가치 | (오버헤드 vs 가치) |
| Downstream 응답 느림 (>500ms) | (thread pool 폭주 우려) | ✅ |
| 대량 동시 사용자 (10k+) | (thread 비용) | ✅ |
| 팀 reactive 익숙도 낮음 | ✅ 학습 비용 0 | (러닝 커브) |
| 디버깅 / observability 우선 | ✅ stack trace 명확 | (Reactor 의 chain 추적 어려움) |
| Streaming 응답 (SSE, WebSocket) | (제약 있음) | ✅ Mono/Flux 자연 |

---

## Measured Results — TBD

### Phase 2 (MVC) 측정 결과
> 측정 후 [BENCHMARK.md](../BENCHMARK.md) 의 Phase 2 섹션에서 가져옴.

```
TBD — Phase 2 완료 시 채움
```

### Phase 6 (WebFlux) 측정 결과
> 측정 후 [BENCHMARK.md](../BENCHMARK.md) 의 Phase 6 섹션에서 가져옴.

```
TBD — Phase 6 완료 시 채움
```

### 직접 비교 표

```
TBD — Phase 6 완료 시:
| Metric                  | MVC Coalesced | WebFlux Coalesced | Diff |
| External API calls       | 1             | 1                 |      |
| P99 latency              | 32ms          | 28ms              |      |
| Tomcat busy thread       | 5%            | N/A               |      |
| WebClient connections    | N/A           | 1                 |      |
| Throughput               | 3,000 req/s   | 3,500 req/s       |      |
```

---

## Visual Comparison — Grafana Dashboards

> Phase 2 + 6 완료 시 캡처 이미지 첨부 예정.

```
[Phase 2 — MVC Baseline]
TBD: Tomcat busy thread 100%, P99 850ms

[Phase 2 — MVC Coalesced]
TBD: Tomcat busy thread 5%, P99 32ms

[Phase 6 — WebFlux Baseline]
TBD: Connection pool 100%, P99 720ms

[Phase 6 — WebFlux Coalesced]
TBD: Connection pool 1, P99 28ms
```

---

## Synthesis — 통합 통찰

> Phase 6 완료 + 측정값 확보 후 작성.

핵심 thesis (예상):

> **같은 thundering herd 패턴이 paradigm 별로 다른 자원으로 표출되지만, 본질은 동일.
> SingleFlight 의 추상화 (Port/Adapter) 가 충분히 일반적이라 paradigm 별 idiom 만 갈아끼면 적응 가능.**

이 통찰이 [THESIS.md](../THESIS.md) 의 L3 → L5 단계의 핵심 시그널.

---

## Code Cross-Reference

### MVC Implementation
- `mvc-demo/src/main/java/com/portfolio/demo/mvc/CoalescedMvcController.java`
- `coordinator-core/src/main/java/com/portfolio/singleflight/coordinator/adapter/InProcessSingleFlightCoordinator.java`

### WebFlux Implementation
- `webflux-demo/src/main/java/com/portfolio/demo/webflux/CoalescedFluxController.java`
- `coordinator-core/src/main/java/com/portfolio/singleflight/coordinator/adapter/ReactiveInProcessCoordinator.java`

---

## Future Extensions (v1.1+)

이 비교 챕터를 다음으로 확장:

| Phase | 비교 대상 | 핵심 dimension |
|---|---|---|
| 7 | Java 21 Virtual Threads | VT 가 thread 비용 줄이지만 connection 은 그대로 |
| 8 | Kotlin Coroutines | `Mutex.withLock` + `Deferred` idiom |

→ **4-paradigm 비교** 가 v1.1 의 어필 정점.

---

## Related

- [STORY.md](../STORY.md) — 7-act narrative
- [DESIGN.md](../DESIGN.md) — Hexagonal + Decorator 설계
- [BENCHMARK.md](../BENCHMARK.md) — Phase 별 측정 결과
- [THESIS.md](../THESIS.md) — L0~L5 진화 모델
- [INTERVIEW.md](../INTERVIEW.md) Q4 — "MVC vs WebFlux 어느 게 더 좋나요?" 답변
