# STORY — How We Stopped Distributed Login from Locking Our Customers Out

> 이 문서는 portfolio 의 **centerpiece narrative** 입니다. 15분 깊은 읽기용.
> 회사명 / 외부 SaaS platform 명 / 정확한 시각·수치는 NDA 보호 위해 익명화.
> 패턴 / timeline / 측정 방법론은 실제 그대로.

---

## Act 1 — Hook

> **2026 년 봄 어느 평일 밤.**
>
> 한 customer 가 자기 매장 답글 20 개를 자동 등록하는 batch 기능을 실행했다.
> 80 초 후, 그 customer 의 외부 SaaS 계정이 1 시간 lock 됐다.
> 외부 시스템은 우리 서버를 *"distributed brute-force login attempt"* 로 감지했다.
>
> 이 글은 그 incident 의 **RCA**, **architectural 해법**,
> 그리고 그 과정에서 발견한 **4 가지 동시성 paradigm 의 본질** 에 대한 기록이다.

---

## Act 2 — The Incident

### Customer A — 영등포의 한 매장 owner

Customer A 는 외식 프랜차이즈 가맹점 owner. 매일 평균 50 건의 신규 리뷰를 받고, 각자 답변을 달아주는 게 본인 업무. 우리 SaaS 의 **batch reply** 기능을 쓰면 답글 작성 시간이 30 분 → 5 분으로 줄어든다. 본인의 일과 끝의 핵심 도구.

### Timeline (KST, 분 단위)

```
T+0    Customer A 가 batch reply 기능 실행 (20개 reply)
       UI 가 1~3초 간격으로 요청 발사 (총 67초 소요)

T+10s  scraper-js 의 ensureSession() 가 Customer A 의 user-X 에 대해 1번째 호출
       → 외부 세션 cache miss → 새 로그인 → proxy A 로 IP 1.2.3.4 할당

T+12s  2번째 reply 요청 도착
       → ensureSession() 동시 호출 (1번째 아직 진행 중)
       → cache 에 1번째 결과 아직 안 박힘 → 또 새 로그인
       → proxy B 로 IP 5.6.7.8 할당

T+15s  3번째 → IP 9.10.11.12
T+22s  4번째 → IP ...
...
T+82s  10번째 다른 IP 할당. (이미 외부 시스템에 boundary signal)

T+90s  외부 시스템 보안 로직 발동:
       "user-X 가 80초 안에 10개 IP 로 로그인 시도"
       → distributed brute-force 감지
       → user-X 계정에 보호조치 발동 (1시간 lock)

T+91s  우리 scraper-js 가 외부 시스템에서 "보호조치 로그인" 페이지 응답 수신
       → 우리 시스템도 처음 본 페이지 (이전엔 정상 로그인 페이지만)

T+95s  Customer A 의 휴대폰에 외부 시스템 발신 알림:
       "새로운 환경에서 로그인이 감지되었습니다" × 5통

T+8min Customer A 가 외부 SaaS 직접 로그인 시도 → 차단됨 → CS 문의
```

### Customer 영향

- 그날 batch reply **불가**
- 1 시간 동안 답글 **수동 작성**으로 대체 (작업 시간 30분 → 30분 + 1시간 대기)
- CS 통화 **17 분**
- 휴대폰 알림 5 통 → 본인이 해킹당한 줄 알고 비밀번호 변경
- 변경된 비밀번호로 다시 우리 SaaS 에 로그인 → 다음 날 batch 시도까지 **24h+ 신뢰 손상**

### 24 h 동안 같은 패턴

이게 단발이 아니었다. 24 h 모니터링:
- **5 명의 customer** 가 동일 차단 발생
- 평균 lock 시간 **47 분** (외부 시스템의 자동 해제 + CS 통화 후 외부 시스템에 정상 로그인)
- 평균 CS 통화 시간 **14 분/건**

Customer Support 팀이 보고: "최근 batch reply 후 차단된다는 문의가 평소 대비 3 배."

→ Engineering 에 escalation. **이 incident 의 RCA 가 시작.**

---

## Act 3 — The Investigation

### 가설 1 — 외부 시스템 일시 장애?

**검증**:
- Datadog 에서 다른 customer 들의 외부 시스템 호출 metric 확인
- 동일 시간 window 에 다른 customer 들은 **정상 응답률**
- → **외부 시스템 자체는 멀쩡**. **우리만 이 customer 들에 대해 차단**.

**기각.**

### 가설 2 — Proxy 풀 문제?

**검증**:
- ISP proxy provider (Decodo) 의 헬스체크 → 정상
- 우리 proxy 풀에서 다른 user 들은 정상 동작
- → **Proxy 자체는 정상**. **할당 패턴이 이상**.

**기각, 단 hint 발견**: "왜 같은 user 인데 80 초에 10 개 IP 가 할당되지?"

### 가설 3 — 인증 정보 만료?

**검증**:
- Customer A 의 비밀번호 변경 이력: 90 일 전이 마지막
- 외부 SaaS 의 토큰 만료 정책: 만료 시 단순 재로그인
- → **인증 정보 만료가 원인이 아님**. 만료라면 1 번만 다시 로그인했어야 함.

**기각.**

### 가설 4 — 우리가 외부 시스템에 비정상 패턴 보낸 건가?

**증거**:
```
22:42:13 ~ 22:43:35 (82초 윈도우)
proxy log 분석:
  - 같은 user-X 에 대한 ensureSession() 호출 20 번
  - 할당된 unique IP 10 개 (proxy 풀 라이브러리의 user lock 깜박이는 시간 동안 새 IP 할당)
```

**핵심 발견**:
> 같은 user 가 80 초 안에 10 개 다른 IP 로 외부 시스템에 로그인 = distributed login attempt 패턴

→ 외부 시스템 입장에선 우리 = **bot net 의 분산 공격**.

### Root Cause

코드 추적:

```typescript
// 의사코드 (실제 코드 아님)
async ensureSession(userId) {
  const cached = await cache.get(userId);
  if (cached && validate(cached)) return cached;

  // ★ 여기 — 동시 N 호출이 모두 이 분기에 도달
  const newSession = await externalLogin(userId);  // 새 proxy IP 할당
  await cache.set(userId, newSession);
  return newSession;
}
```

→ **per-user 직렬화 부재.** 동시 호출이 cache lookup 모두 miss → 각자 새 외부 로그인 → 각자 다른 proxy IP.

이 함수는 코드베이스 전체에서 외부 SaaS 호출의 entry point. 비즈니스 로직이 점점 복잡해지면서 설계 시 가정한 "동시 호출 거의 없을 것" 이 무너졌다. Batch 기능이 추가되면서 명시적으로 동시 호출 패턴이 발생.

---

## Act 4 — The Decision

해법으로 4 가지 옵션 검토.

### 대안 A — 단순 동기 lock

```typescript
const lock = new Mutex();
async ensureSession(userId) {
  await lock.acquire();
  try {
    /* 기존 로직 */
  } finally {
    lock.release();
  }
}
```

**Trade-off**:
- ✅ 가장 단순, 즉시 incident 차단
- ❌ **모든 user 가 직렬화** → user-A 가 ensureSession 도는 동안 user-B 도 대기 (over-locking)
- ❌ Throughput 폭락
- ❌ Customer A 1명 보호하려고 모든 customer feedback latency 증가

**기각** — over-locking.

### 대안 B — Per-user lock (synchronized block per userId)

```typescript
const userLocks = new Map<string, Mutex>();
async ensureSession(userId) {
  if (!userLocks.has(userId)) userLocks.set(userId, new Mutex());
  await userLocks.get(userId).acquire();
  /* ... */
}
```

**Trade-off**:
- ✅ 같은 user 만 직렬화
- ❌ N waiter 가 모두 외부 호출 N 번 (대기 후 각자 호출)
- ❌ N 번 외부 호출 자체가 thundering herd → 외부 시스템 다시 차단
- ❌ Multi-instance 환경에선 무력 (JS 단일 process 한정)

**기각** — 차단 문제 안 풀림.

### 대안 C — Redis 분산 lock

```typescript
const lock = await redis.acquireLock(`session:${userId}`);
/* ... */
await lock.release();
```

**Trade-off**:
- ✅ Multi-instance 커버
- ✅ User-level 직렬화
- ❌ 모든 호출에 latency overhead 3-10ms (Redis RTT)
- ❌ Redis 장애 시 전체 ensureSession 막힘
- ❌ 결과 공유 메커니즘이 별도로 필요 (lock 후에 내가 외부 호출할지, 누가 했으면 결과 가져올지)

**기각, 단 P1 후보로 보존** — 멀티 인스턴스 시 다시 검토 (Phase 3 의 ADR-002 에서).

### 대안 D — Single-Flight 패턴 (Go singleflight 모델) ★ 채택

```typescript
const inflight = new Map<userId, Promise<Session>>();
async ensureSession(userId) {
  if (inflight.has(userId)) return inflight.get(userId);  // waiter
  const promise = externalLogin(userId).finally(() => inflight.delete(userId));
  inflight.set(userId, promise);
  return promise;
}
```

**Trade-off**:
- ✅ **Per-user 직렬화 + 결과 공유** (1 owner + N waiter)
- ✅ 외부 호출 1 번 (대안 B 대비 N 분의 1)
- ✅ Latency overhead 거의 0 (메모리 lookup)
- ✅ 단일 인스턴스에서 충분, 멀티 인스턴스로 점진적 확장 가능
- ⚠️ 단일 process 한정 → 미래에 Redis layer 필요할 수 있음

**채택**. **Single-Flight + Hexagonal (multi-instance 확장 여지) + Decorator stack (운영 안전망)** 을 종합 설계.

### Meta-Decision — 왜 Hexagonal 까지 했는가?

위 4 alternative 는 **lock 메커니즘** 의 비교. 그런데 Single-Flight 채택 후 추가 결정이 하나 더:

> "Single-Flight 를 NaverService 에 inline 으로 박을 것인가, Port/Adapter 추상화로 분리할 것인가?"

이게 **architectural-level decision**. 두 옵션:

#### Meta-Option α — Inline (단순 utility)

```java
// NaverService.java
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

→ **30 줄 추가로 incident 즉시 차단.** YAGNI 정직.

**Pros**:
- 가장 단순. Boilerplate 0.
- 빠른 시작. 즉시 production 배포 가능.

**Cons**:
- ❌ Multi-instance 갈 때 NaverService 다시 수정
- ❌ Telemetry / Deadline / Capacity 도 NaverService 안에 박힘 (SRP 위반)
- ❌ 다른 도메인 (CPEATS, BAEMIN) 에 같은 패턴 적용 시 코드 중복

#### Meta-Option β — Hexagonal (Port/Adapter)

```java
public interface SingleFlightCoordinator { ... }

class InProcessSingleFlightCoordinator implements SingleFlightCoordinator { ... }
class RedisSingleFlightCoordinator implements SingleFlightCoordinator { ... }

@Service
public class NaverService {
    private final SingleFlightCoordinator coordinator;  // Port 만 의존
    public CompletableFuture<Session> ensureSession(String userId) {
        return coordinator.execute(userId, () -> doEnsureSession(userId));
    }
}
```

→ **약 1000 줄** (Port + Adapter + Decorator 4개 + 테스트). 약 3-5일 작업.

**Pros**:
- ✅ Multi-instance 갈 때 NaverService 수정 0
- ✅ Decorator 4개 (Telemetry / Heartbeat / Capacity / Deadline) SRP 분리
- ✅ 다른 도메인 재사용
- ✅ Framework 독립성

**Cons**:
- ❌ 약 30× 코드 양 (단순 인라인 대비)
- ❌ 추상화 학습 비용
- ❌ 단일 backend 평생 쓸 거면 over-engineering

#### 채택 — Meta-Option β

근거 4 시그널 ([ADR-001](adr/ADR-001-port-adapter-pattern.md) 의 "Decision Signals"):

| 시그널 | 우리 case |
|---|---|
| 여러 backend 실제 필요 | ✅ Phase 3 Redis 명시 |
| 도메인 코드 backend 영향 X | ✅ NaverService 보호 |
| Port API 안정성 | ✅ 4 메서드 충분 |
| Framework 독립성 가치 | ✅ Spring 0 의존 |

**4/4 해당.** Hexagonal 의 추가 cost (약 1000줄) 가 정당화되는 case.

#### 만약 시그널 0~1개만 해당했다면?

→ **Meta-Option α (inline) 가 정답.** Over-engineering 회피. 두 번째 backend 가 명백해진 시점에 그때 추상화 (refactoring with TDD).

이 판단이 시니어 시그널: **"Hexagonal 자체에 대한 고집이 아니라 case 분석"**. 만약 모든 프로젝트에 무조건 Hexagonal 적용한다면 그게 cargo cult.

---

## Act 5 — The Solution

### Architecture — Hexagonal Port/Adapter + Decorator Stack

```
NaverService.ensureSession()
        │
        ▼
SingleFlightCoordinator (Port)        ← 인터페이스만 의존
        │
        ▼
[Telemetry Decorator]                  ← outermost: 로그 / 메트릭 / waiter count
        │
        ▼
[Heartbeat Decorator]                  ← 60s+ 진행 중 entry 경고
        │
        ▼
[Capacity Decorator]                   ← 30+ waiter 거부 (load shedding)
        │
        ▼
[Deadline Decorator]                   ← 180s wall-clock 타임아웃
        │
        ▼
InProcessSingleFlightCoordinator       ← Base: ConcurrentHashMap.computeIfAbsent
        │
        ▼
ensureSessionImpl() (Playwright 영역)
```

상세 설계: [DESIGN.md](DESIGN.md)

### Why Hexagonal?

`SingleFlightCoordinator` 가 인터페이스라서 **backend swap 이 NaverService 코드 수정 없이 가능**:
- 단일 인스턴스 → in-process Map
- 멀티 인스턴스 → Redis SETNX + pub/sub (Phase 3, ADR-002)
- (미래) MQ routing 도 추상화 합성으로

### Why Decorator?

운영 안전망 4개를 SOLID 원칙으로 분리:
1. **Telemetry** — observability (로그 + 메트릭)
2. **Heartbeat** — 60s+ stuck 조기 감지
3. **Capacity** — UI spam 방어 (cap 초과 거부)
4. **Deadline** — 외부 hang 강제 종료 (180s wall-clock)

각자 단위 테스트 가능. Stack 순서가 의미 있음 (ADR-003 참고).

### Implementation Phases

| Phase | 내용 | Status |
|---|---|---|
| 1 | coordinator-core (Pure Java) | 🔜 |
| 2 | mvc-demo + 측정 | 🔜 |
| 3 | redis-adapter (multi-instance) | 🔜 |
| 4 | TS reference + cross-language | 🔜 |
| 6 | webflux-demo (reactive) | 🔜 |
| 5 | polish | 🔜 |

[Issues](https://github.com/PreAgile/single-flight-coordinator-lab/issues) 에서 진행 상황 추적.

---

## Act 6 — The Result

### 기술 임팩트 (정량)

> ⚠️ 아래 측정값은 실 운영 incident 의 추정값입니다. 본 repo 의 [BENCHMARK.md](BENCHMARK.md) 에서 재현 가능한 합성 부하 + Phase 2 측정으로 검증 예정.

| 항목 | Before (without single-flight) | After (with single-flight) | Δ |
|---|---|---|---|
| 외부 호출 수 (동시 20 요청) | 20 | 1 | **20× 감소** |
| 다른 IP 할당 (80초 윈도우) | 10 | 1 | **10× 감소** |
| P99 latency | ~850ms | ~32ms | **26× 빠름** |
| 외부 시스템 보안 차단 | 발생 | 0 (24h 모니터링) | **완전 차단** |
| 영향받은 customer (24h) | 5 명 | 0 명 | **0** |

### 비즈니스 임팩트 (정량 추정)

```
[Customer Support 비용]
  - 평균 lock incident 처리 시간: 14 분
  - 24h 동안 incident: 5건 → 월 환산: ~150건
  - CS 시급 (시장 평균): X 원
  - 월 CS 비용: 150 × 14분 × X원 = N1 원

[Customer 이탈 위험]
  - lock 후 24h 미접속 customer: P% (lock 직후 측정)
  - 월간 churn 추가: P% × 150 = M 명
  - 1 인당 평균 매출 (LTV 기반): R 원
  - 월간 매출 손실: M × R = N2 원

[합산 회피 가치]
  연간 회피 ≈ (N1 + N2) × 12 ≈ 약 N 원

[정성 위험]
  - 외부 시스템 IP 풀 blacklist 가능성 (서비스 전면 중단 risk)
  - 평판 손상 (외부 SaaS 가 우리 서비스 차단 발표 시)
  - Compliance 위험 (외부 시스템 ToS 위반 의심)
```

→ **단순 기술 개선이 아니라 사업 위험 회피.** ([BENCHMARK.md](BENCHMARK.md) 의 비즈니스 임팩트 framework 참고)

### Counterfactual — 안 고쳤다면

- batch reply 기능 사용량 증가 추세 (월 +15% 도입률)
- Incident 빈도도 비례 증가 → 6 개월 후 월 ~300 건 예상
- 외부 시스템이 차단 정책 강화 시 → 차단 시간 1시간 → 24시간 가능
- 차단 누적 → 외부 시스템에서 우리 IP 풀 blacklist → **서비스 전면 중단 시나리오**

→ "고쳤으니 다행" 이 아니라 **"안 고쳤으면 회사가 위험" 이었던 부류**.

---

## Act 7 — The Lesson

### 무엇을 배웠나

**1. 외부 시스템의 보안 알고리즘은 우리에게 비기능 요구사항이다**

정상적인 우리 동작이 외부에선 비정상으로 보일 수 있다. API 호출 패턴 자체가 SLA 의 일부.
→ Engineering onboarding 에 외부 시스템의 보안 정책 문서화 추가.

**2. "외부 자원 할당" 함수는 본질적으로 single-flight 가 필요**

이런 함수의 특징:
- 호출 비용이 큼 (외부 호출, 네트워크, 자원 할당)
- 동시 호출이 동일 결과를 만들어야 함 (idempotent)
- 부수 효과가 외부 시스템에 의미 있음

→ 코드 리뷰 시 이런 함수 보이면 single-flight 패턴 검토.

**3. Measurement-driven development 의 진짜 의미**

"P99 좋아짐" 이 아니라 **"외부 호출 N → 1"** 처럼 비즈니스 임팩트와 직결되는 메트릭이 진짜 무기. portfolio 도 같은 원칙.

**4. 동시성 paradigm 별 같은 문제의 다른 표출**

이 incident 가 thundering herd 의 한 형태였고, MVC / WebFlux / Loom / Coroutines 어느 paradigm 에서도 발생. 다만 표출되는 자원이 다름 (thread / connection / VT count). 새 framework 만나도 같은 사고로 적응 가능. ([THESIS.md](THESIS.md) 의 L4-L5)

### 다시 하면 다르게 할 것

1. **ensureSession 을 코드 작성 시점에 single-flight 로 짰을 것** (incident 후 fix 하지 말고). 외부 자원 할당 함수의 default 패턴.
2. **외부 시스템의 보안 정책을 문서화** 했어야 함. 어떤 호출 패턴이 brute-force 로 감지되는지.
3. **proxy IP 할당 로그를 incident 전에 alerting** 했어야 함. 80초 10 IP 임계값. → 사전에 차단 가능했음.
4. **Customer-impacting incident 의 회고가 늦었음**. CS 팀의 "평소 대비 3배" 신호가 더 빨리 escalation 됐어야 함.

### 이 패턴이 일반화되는 곳

Single-Flight 는 thundering herd 의 한 표준 해법. 적용 가능한 곳:

| 도메인 | 적용 사례 |
|---|---|
| Cache | Cache stampede 방지 (만료 시 동시 backing store 호출) |
| 외부 API | Rate limit 보호 (동시 동일 resource 호출 합치기) |
| 비싼 계산 | 동일 input 의 동시 계산 합치기 (deduplicated computation) |
| 분산 시스템 | Leader election, 분산 lock 의 single-flight layer |
| DB | Connection pool 의 connection 획득 race |
| Auth | Token refresh 동시 호출 합치기 |

이 repo 의 패턴은 **외부 API 의 보안 차단** 맥락에서 출발했지만, 본질은 **"동일 key 의 동시 호출을 1번으로 합치는"** 보편 패턴.

---

## 후기 — 이 글이 의도하는 것

이 STORY 는 portfolio 의 한 piece 이기 전에 **본인의 회고**입니다.

배운 것:
- 시니어급 architectural 사고가 무엇인지 (대안 4개를 비교하고 trade-off 명시)
- 비즈니스 임팩트와 기술 임팩트를 같이 보는 사고
- 외부 의존성의 행동을 우리 시스템 SLA 의 일부로 인식하기

배운 것을 글로 정리하지 않으면 6개월 후 잊는다. 이 글이 미래의 본인에게 **"왜 이 코드를 이렇게 짰는지"** 답변이 되길.

그리고 portfolio 측면에서, 이 글은 면접관에게 **"이 사람이 단순 패턴 구현 능력만이 아니라 비즈니스 사고 + 회고 능력 + 일반화 능력 가진 사람"** 이라는 시그널.

---

## 관련 문서

- [INCIDENT.md](INCIDENT.md) — Customer A 의 인간적 narrative + 상세 timeline
- [DESIGN.md](DESIGN.md) — Hexagonal + Decorator architecture 상세
- [BENCHMARK.md](BENCHMARK.md) — 측정 방법론 + 비즈니스 임팩트 framework
- [INTERVIEW.md](INTERVIEW.md) — 1분 / 5분 (STAR) / 딥다이브 답변 스크립트
- [THESIS.md](THESIS.md) — 이 portfolio 의 진화 thesis (L0 → L5)
- [adr/](adr/) — Architecture Decision Records 5개
- [GitHub Issues](https://github.com/PreAgile/single-flight-coordinator-lab/issues) — 6 Phase 진행 상황
