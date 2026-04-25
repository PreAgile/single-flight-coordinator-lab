# ADR-001 — Adopt Port/Adapter (Hexagonal) Pattern for Single-Flight Coordinator

| Field | Value |
|---|---|
| Status | **Proposed** (Phase 1 구현 완료 시 Accepted) |
| Date | 2026-04-25 |
| Phase | 1 |
| Deciders | @PreAgile |
| Related | [ADR-002](ADR-002-in-process-vs-redis-lock.md), [ADR-003](ADR-003-decorator-stack-order.md), [ADR-004](ADR-004-coordinator-vs-caffeine.md) |

---

## Context

운영 incident ([INCIDENT.md](../INCIDENT.md)) 의 RCA 로 Single-Flight 패턴 도입 결정.
구현 시 architectural 선택지가 여러 개:

1. **단일 클래스 직접 구현** — `NaverService` 안에 inline 으로 Map + Promise.race
2. **Port/Adapter (Hexagonal)** — 인터페이스 정의 + 여러 backend 구현 가능
3. **Spring AOP `@Around`** — Bean 메서드에 cross-cutting 으로 주입

선택 기준:
- 도메인 로직 (NaverService) 의 변경 최소화
- 미래 확장 (in-process → Redis → MQ routing) 에 유연
- 테스트 용이성
- Framework 의존성 최소

---

## Decision

**Port/Adapter (Hexagonal) 패턴 채택.**

### Decision Signals — 왜 이 case 가 Hexagonal 정당화되는가

Hexagonal 은 **항상 정답이 아니다.** Over-engineering 의 흔한 함정.
다음 4 시그널 중 **2 개 이상** 해당해야 추상화 비용이 정당화된다.

| # | 시그널 | 우리 case 적용 여부 | 증거 |
|---|---|:---:|---|
| **S1** | 여러 backend 구현이 실제로 필요 | ✅ | Phase 3 (Redis) + 미래 MQ routing 명시. ADR-002 가 backend swap 비교. |
| **S2** | 도메인 코드가 인프라 변경 영향받지 않아야 | ✅ | NaverService 가 `SingleFlightCoordinator` Port 만 의존. Phase 1→3 transition 시 NaverService 수정 0. |
| **S3** | Port API 가 안정적 (자주 안 바뀜) | ✅ | 4 메서드 (execute / execute+opts / getInflightState / forceRelease). Phase 1~6 진행 동안 변동 없을 예정. |
| **S4** | Framework 독립성이 가치 있음 | ✅ | `coordinator-core` 가 Spring 의존성 0. Quarkus / Micronaut / plain Java 어디든 임베드 가능. Portfolio 측면 시그널. |

**4/4 해당** → Hexagonal 채택 정당화 충분.

### 만약 0~1 개만 해당했다면?

[Alternative A](#alternative-a--direct-implementation-in-naverservice) (단순 utility class, 30줄) 가 더 적절.
**YAGNI 정직하게 따르는 게 시니어 시그널.** Hexagonal 자체에 대한 고집은 cargo cult.

### Port — `SingleFlightCoordinator` 인터페이스

```java
public interface SingleFlightCoordinator {
    <T> CompletableFuture<T> execute(String key, Supplier<CompletableFuture<T>> operation);
    <T> CompletableFuture<T> execute(String key, Supplier<CompletableFuture<T>> operation, SingleFlightOptions options);
    List<InflightEntry> getInflightState();
    CompletableFuture<Void> forceRelease(String key, String reason);
}
```

도메인 service (NaverService 같은) 는 이 인터페이스만 의존.

### Adapter Variants

- **InProcess** (Phase 1) — `ConcurrentHashMap.computeIfAbsent`. 단일 JVM.
- **Redis** (Phase 3) — `SETNX + Lua + Pub/Sub`. 멀티 인스턴스.
- **(미래)** MQ routing layer — InProcess 와 합성.

### 합성 — Decorator Stack

[ADR-003](ADR-003-decorator-stack-order.md) 참고. 4 decorator (Telemetry / Heartbeat / Capacity / Deadline) 가 inner adapter 를 wrap.

---

## Consequences

### Positive

✅ **Backend swap**: NaverService 코드 수정 없이 in-process → Redis → MQ 전환 가능.
✅ **Framework-independent core**: `coordinator-core` 모듈은 Spring 의존성 0. 어떤 Java framework (Spring, Quarkus, Micronaut, plain Tomcat) 에든 임베드 가능.
✅ **Testability**: 각 Adapter / Decorator 가 단위 테스트 가능. Mock Coordinator 로 NaverService 도 격리 테스트.
✅ **Composition**: Decorator 4개를 필요한 조합으로 stack. 환경별 다른 stack (개발/프로덕션) 가능.
✅ **Portfolio 시그널**: Hexagonal Architecture 이해 + 추상화 깊이 어필.

### Negative

❌ **간접화 비용**: 단순 use case 에 인터페이스 + 구현 + factory 가 boilerplate.
❌ **Generic type 복잡성**: `<T>` 가 Java 타입 시스템에서 어색 (cast 필요한 영역 있음).
❌ **DI 설정 복잡**: 5 layer chain 합성을 factory 함수에서 명시. 단순 `@Bean` 만으론 부족.

### Neutral

⚪ **러닝 커브**: 새 팀원이 합성 chain 이해하는 데 시간 필요. 단 [DESIGN.md](../DESIGN.md) 가 답.

---

## Alternatives Considered

### Alternative A — Direct Implementation in NaverService

```java
@Service
public class NaverService {
    private final ConcurrentHashMap<String, CompletableFuture<Session>> inflight = new ConcurrentHashMap<>();

    public CompletableFuture<Session> ensureSession(String userId) {
        return inflight.computeIfAbsent(userId, k ->
            doEnsureSession(k).whenComplete((v, e) -> inflight.remove(k))
        );
    }
}
```

**Pros**:
- 단순. Boilerplate 0.
- Phase 1 코드 50% 감소.

**Cons**:
- ❌ Multi-instance 갈 때 NaverService 직접 수정 (도메인 vs 인프라 분리 깨짐)
- ❌ Telemetry / Deadline / Capacity 도 NaverService 안에 박혀야 함 (SRP 위반)
- ❌ 다른 도메인 (CPEATS, BAEMIN) 에 같은 패턴 적용 시 코드 중복
- ❌ Portfolio 측면에서 architectural 깊이 부족

**Verdict**: Phase 1 단독 보면 합리적이지만, **Phase 3 (Redis 확장) + 다른 도메인 적용 시점에 부담 폭발**. 미리 추상화.

---

### Alternative B — Spring AOP `@Around`

```java
@Aspect
@Component
public class SingleFlightAspect {
    private final ConcurrentHashMap<String, CompletableFuture<?>> inflight = new ConcurrentHashMap<>();

    @Around("@annotation(SingleFlight)")
    public Object coalesce(ProceedingJoinPoint pjp, SingleFlight ann) throws Throwable {
        String key = extractKey(pjp, ann.keyExpression());
        return inflight.computeIfAbsent(key, k -> {
            try { return (CompletableFuture<?>) pjp.proceed(); }
            catch (Throwable e) { throw new RuntimeException(e); }
        });
    }
}

@Service
public class NaverService {
    @SingleFlight(keyExpression = "#userId")
    public CompletableFuture<Session> ensureSession(String userId) { ... }
}
```

**Pros**:
- 사용자 편의 (annotation 하나로 적용)
- Bean 단위 적용 자연스러움

**Cons**:
- ❌ **Spring 강결합** — coordinator-core 가 framework-independent 라는 원칙 어김
- ❌ Proxy chain 디버깅 어려움 (stack trace 가 Spring CGLib 으로 채워짐)
- ❌ Cross-cutting 다중 concern (deadline + capacity + telemetry) 각자 aspect 면 stack 순서 모호
- ❌ Test 시 Spring context 필수 (단위 테스트 무거움)
- ❌ Decorator 의 명시적 stack 보다 가독성 약함

**Verdict**: Spring 헤비 환경에선 매력적이지만, **library-first portfolio** 가치엔 안 맞음.

---

### Alternative C — Library 직접 사용 (Caffeine.AsyncCache)

```java
AsyncLoadingCache<String, Session> cache = Caffeine.newBuilder()
    .expireAfterWrite(Duration.ofMinutes(5))
    .buildAsync(key -> doEnsureSession(key));

CompletableFuture<Session> session = cache.get("user-X");  // coalesce 내장
```

**Pros**:
- 작성 코드 0
- 검증된 라이브러리 (millions of downloads)

**Cons**:
- ❌ Cache 가정 하에 동작 (TTL, eviction). 우리 use case 는 cache 가 아니라 session 외부 자원 할당
- ❌ Deadline / Capacity / Telemetry 통합 제어 불가 (각각 Caffeine 외부에서 별도 wrap)
- ❌ Caffeine 강결합 → 미래 Redis backend 로 swap 어려움 (Caffeine 인터페이스 구현 안 함)
- ❌ Portfolio 측면: "Caffeine 썼어요" 보다 "직접 구현했고 그 이유는..." 이 시니어 시그널

상세 비교: [ADR-004](ADR-004-coordinator-vs-caffeine.md)

**Verdict**: 일부 use case 에는 적합하지만, 운영 안전망 통합 + multi-backend 확장이 핵심 목적이라 직접 구현.

---

## When NOT to Use This Pattern — 반대 점검

이 ADR 의 목적은 **"Hexagonal 을 모든 곳에 권장하는 게 아니라"**, 우리 case 가 정당한지 점검하는 것. 다음 4 anti-signal 중 **2 개 이상** 해당하면 Hexagonal 은 over-engineering.

### Anti-Signal A1 — 단일 backend 평생 쓸 가능성 높음

- 예: 작은 startup 의 마이크로서비스가 PostgreSQL 만 쓰고 영원히 그럴 것
- 미래에 새 backend 추가 가능성 < 30%
- → 미리 추상화 비용 > 가치

**우리 case**: ❌ 해당 안 됨. Phase 3 의 Redis adapter 가 명시적 다음 단계.

### Anti-Signal A2 — Port 가 thin pass-through

- 인터페이스가 사실상 1 구현체 시그니처 그대로 복사
- 추상화의 의미가 없음 (변환 / 정책 / 합성 layer 없음)
- → 인터페이스 + 구현 = boilerplate

**우리 case**: ❌ 해당 안 됨. Port 의 4 메서드가 backend 별 다른 의미 추상:
- `execute` — InProcess: `computeIfAbsent`. Redis: `SETNX + Lua`. MQ: consistent-hash routing.
- `getInflightState` — InProcess: Map snapshot. Redis: `KEYS pattern + GET`.
- `forceRelease` — InProcess: Map remove. Redis: Lua delete.

추상화의 의미가 명확.

### Anti-Signal A3 — 팀 작고 코드베이스 작고 변경 빈도 낮음

- 추상화 maintenance cost > 가치
- 새 팀원이 이해하는 데 비용 > 추상화의 코드 보호 효과
- → 단순 utility class 가 정답

**우리 case**: 부분 해당 (portfolio repo 라 small). 단:
- 변경 빈도가 낮은 게 오히려 Port 안정성 시그널 (S3)
- 다른 도메인 (CPEATS, BAEMIN) 에 같은 패턴 적용 가능성도 고려
- → 부분 해당이지만 다른 시그널이 압도

### Anti-Signal A4 — Framework 강결합 OK

- 평생 Spring 만 쓸 거라면 Spring AOP 가 더 단순
- coordinator-core 의 framework 독립성이 비용 이상의 가치 못 만들면 Hexagonal 약화

**우리 case**: ❌ 해당 안 됨. Portfolio 측면에서 framework 독립성 자체가 시니어 시그널. `coordinator-core` 의 의존성 그래프에 Spring 0.

### 점검 결과

```
Decision Signals (정당화):  4 / 4 해당  →  강한 정당화
Anti-Signals (반대):        0~1 / 4 해당  →  약한 반대
```

→ **순 정당화 4점.** Hexagonal 채택 정당.

만약 **Anti-Signal 이 2개 이상 해당했다면**: ADR-001 의 Decision 을 [Alternative A](#alternative-a--direct-implementation-in-naverservice) (직접 구현) 로 revision.

---

## Cost-Benefit Analysis

추상화의 **비용 vs 이익** 정량화 (best effort):

### Cost (추상화 비용)

```
[코드 작성 비용]
  - Port interface (40 lines)
  - InProcess adapter (60 lines, 단순 구현 대비 +20 lines)
  - 4 Decorator (각 60 lines × 4 = 240 lines)
  - Factory + DI 설정 (50 lines)
  - 단위 테스트 (300 lines, 단순 utility 대비 +200 lines)
  - 문서화 (ADR + DESIGN 약 800 lines)
  
  → 총 추상화 overhead 약 1,000 lines, 약 3-5 일 작업

[유지보수 비용]
  - 새 팀원 onboarding 시 추상화 chain 이해 (2-3 시간)
  - 새 backend 추가 시 Port 시그니처 검증 (1 일 / backend)
  - Decorator 순서 변경 시 ADR 갱신
```

### Benefit (이익)

```
[기술적 이익]
  - Phase 3 Redis 추가 시 NaverService 수정 0 (절약 ~2 일)
  - 다른 도메인 (CPEATS, BAEMIN) 에 같은 패턴 적용 (절약 ~5 일/도메인)
  - 테스트 격리 (각 layer 독립 — 디버깅 시간 절약)
  - 환경별 stack 변경 가능 (dev/prod)

[Portfolio 이익]
  - "Hexagonal 이해" 시그널 (면접 가치)
  - ADR + 다이어그램 → architecture 토론 자료
  - "이 라이브러리는 framework 무관" 발언 가능
  - cross-paradigm (MVC vs WebFlux) 비교 가능 (같은 Port 두 backend)

[비즈니스 이익 — 회사 컨텍스트]
  - Phase 3 Redis 도입 시 incident free transition (이미 검증된 추상화)
  - 다음 6개월에 새 도메인 적용 시 코드 재사용
```

### Verdict

```
Cost ≈ 1000 lines + 3-5 일
Benefit ≈ Phase 3 transition 절약 + 다른 도메인 재사용 + portfolio + 미래 backend
```

**Phase 3 의 Redis adapter 가 첫 번째 ROI 검증 포인트.** 만약 Phase 3 가 NaverService 수정 0 으로 통과하면 Cost 회수 + 다음 backend 마다 추가 ROI.

만약 Phase 3 에서 Port 시그니처 변경이 필요해지면? → ADR-001 revision + 추상화의 한계 인식 (정직한 회고).

---

## Implementation Notes

### Module Structure

```
coordinator-core/                Pure Java (no Spring)
├── coordinator/
│   ├── SingleFlightCoordinator.java       (Port)
│   ├── SingleFlightOptions.java
│   ├── InflightEntry.java
│   ├── adapter/
│   │   ├── InProcessSingleFlightCoordinator.java   (Phase 1)
│   │   └── RedisSingleFlightCoordinator.java       (Phase 3)
│   ├── decorator/
│   │   ├── DeadlineDecorator.java
│   │   ├── CapacityDecorator.java
│   │   ├── HeartbeatDecorator.java
│   │   └── TelemetryDecorator.java
│   └── exception/...

mvc-demo/                        Spring MVC consumer
└── coordinator-core 의존
└── factory @Bean 으로 chain 합성
```

### Factory 패턴 — 합성

```java
@Configuration
public class CoordinatorBeans {
    @Bean
    public SingleFlightCoordinator naverEnsureSessionCoordinator(
        MetricRegistry metrics,
        SingleFlightProperties props
    ) {
        SingleFlightCoordinator base = new InProcessSingleFlightCoordinator();

        SingleFlightCoordinator stacked = new TelemetryDecorator(
            new HeartbeatDecorator(
                new CapacityDecorator(
                    new DeadlineDecorator(base, props.getDefaultDeadline()),
                    props.getMaxWaiters()
                ),
                Duration.ofSeconds(15),
                Duration.ofSeconds(60)
            ),
            metrics,
            "naver-ensure-session"
        );

        return stacked;
    }
}
```

→ Decorator 순서 명시적. 환경 (개발/프로덕션) 별 stack 변경 가능.

### Test Strategy

각 layer 단위 테스트 + 합성 통합 테스트:

```
unit:
  InProcessSingleFlightCoordinatorTest    — 7 시나리오
  DeadlineDecoratorTest                    — 4 시나리오
  CapacityDecoratorTest                    — 4 시나리오
  HeartbeatDecoratorTest                   — 3 시나리오
  TelemetryDecoratorTest                   — 5 시나리오

integration:
  FullChainTest                            — 5 layer 합성 (4 시나리오)
                                             • coalesce
                                             • deadline propagation
                                             • capacity rejection
                                             • telemetry classification
```

→ 각 단위 테스트는 Mock 또는 spy 로 inner 격리. 합성 테스트는 실 chain 사용.

---

## Validation — How We'll Know This Is Right

### Phase 1 완료 시 확인:
- [ ] `coordinator-core` 가 `@SpringBootApplication` 없이 빌드/테스트 가능
- [ ] `coordinator-core` 의 `build.gradle.kts` 에 Spring 의존성 0
- [ ] 단위 테스트 27개 통과
- [ ] mvc-demo 가 `coordinator-core` 를 deps 로 import 후 즉시 사용 가능

### Phase 3 완료 시 확인:
- [ ] `RedisSingleFlightCoordinator` 추가만으로 NaverService 코드 0 수정
- [ ] `application-coalesce-redis.yml` profile 토글로 backend 전환
- [ ] 4 Decorator 가 in-process 와 Redis backend 모두에 작동

### 만약 이게 깨지면 (이 ADR 가 잘못된 경우):
- "재구현 시 NaverService 도 수정해야 했다" → 추상화가 새는 신호
- "Decorator 의 inner 가 backend specific 동작에 의존" → port 가 충분히 추상적이지 않음
- "Test 가 무거워짐 (Spring 필요)" → coordinator-core 의 의존성 침범

→ 위 시그널 발견되면 ADR-001 revision.

---

## References

- [Hexagonal Architecture by Alistair Cockburn](https://alistair.cockburn.us/hexagonal-architecture/)
- [Decorator Pattern (GoF)](https://en.wikipedia.org/wiki/Decorator_pattern)
- [Effective Java 3rd ed., Item 18 — Composition over inheritance](https://www.oreilly.com/library/view/effective-java-3rd/9780134686097/)
- Go `singleflight` 패키지: https://pkg.go.dev/golang.org/x/sync/singleflight (명명의 어원)
- 도메인 service 의 [INCIDENT.md](../INCIDENT.md), [STORY.md](../STORY.md)

---

## Status History

- 2026-04-25: Proposed (Phase 1 시작 시점)
- TBD: Accepted (Phase 1 구현 완료 + Phase 2 demo 가 정상 동작 확인 시)
