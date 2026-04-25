# INTERVIEW — Talk Track for This Project

> 면접 답변 스크립트. STAR (Situation / Task / Action / Result) 프레임워크.
> 1분 / 5분 / 딥다이브 3 단계. 자주 받을 꼬리질문 7개에 대한 답변까지.

---

## Pre-flight Checklist — 면접 직전 체크

면접 30분 전 자가 점검:

- [ ] [STORY.md](STORY.md) 의 7-act 흐름이 머릿속에 있는가
- [ ] 핵심 측정값 4개를 외우고 있는가:
  - 외부 호출 동시 20요청 시 20 → 1
  - 다른 IP 할당 80초 윈도우 10 → 1
  - P99 850ms → 32ms (예상치)
  - 24h 동안 lock incident 5건 → 0건
- [ ] 대안 4개의 trade-off 를 화이트보드에 그릴 수 있는가
- [ ] 5 layer Decorator 의 순서 이유를 즉답할 수 있는가
- [ ] [GitHub repo URL](https://github.com/PreAgile/single-flight-coordinator-lab) 알고 있는가

---

## 1-Minute Version

### 자기소개에서 (프로젝트 한 줄)

> "B2B SaaS 외부 API 연동 시스템에서 동시 요청이 외부 시스템에 distributed login 으로 감지되어 보안 차단 걸리는 incident 를 겪었습니다. SingleFlight 패턴을 Hexagonal + Decorator 로 도입해서 풀었고, 그 패턴을 Java / Spring MVC / WebFlux 로 재구현하면서 각 paradigm 의 thundering herd 표출 차이를 측정값으로 비교한 portfolio piece 입니다. GitHub 에 올라가있고, 6 Phase roadmap 으로 진행 중입니다."

→ 약 50초. **Hook + 패턴 + 비교 어필 + 진행 시그널** 4 요소.

---

## 5-Minute Version (STAR)

### Situation (1분) — 상황

> "**B2B SaaS 외부 API 연동 시스템**에서, 한 customer 가 batch reply 기능 사용 중 외부 시스템의 보안 차단을 트리거하는 incident 가 발생했습니다.
>
> 구체적으로는 같은 user 의 동시 요청 20개가 1~3초 간격으로 우리 시스템에 도착했고, 80초 안에 10개 다른 proxy IP 로 외부 시스템에 로그인 시도가 감지되어 외부 시스템이 distributed brute-force 로 판단, 그 customer 의 계정을 1시간 lock 시켰습니다.
>
> 24시간 모니터링 결과 5명의 customer 에서 동일 패턴이 발생했고, customer 평균 lock 47분 + CS 통화 14분/건 + 신뢰 손상 + 향후 외부 시스템이 우리 IP 풀을 blacklist 할 위험까지 사업 영향이 누적되는 상황이었습니다."

키 포인트:
- 구체적 수치 (80초 / 10 IP / 5명 / 47분 / 14분)
- 사업 영향 명시
- "내가 발견했다" 가 아니라 incident 자체를 객관적으로

### Task (30초) — 책임

> "팀에서 제가 이 incident 의 RCA 와 architectural 해법 설계를 담당했습니다. 책임 범위는:
> 1. Root cause 식별
> 2. 같은 패턴 재발 방지 설계
> 3. 미래 multi-instance 환경 확장 가능성까지 고려한 아키텍처
> 4. Customer 영향 측정 및 비즈니스 임팩트 정량화"

키 포인트: **본인 책임 명확화**. "팀이 했어요" 가 아니라 "내가 이걸 담당".

### Action (2.5분) — 무엇을 했나

> "Single-Flight Coordinator 패턴을 Hexagonal Architecture (Port/Adapter) + Decorator stack 으로 분리해서 도입했습니다.
>
> **Hexagonal 채택 이유**: 단일 인스턴스 in-process Map 으로 시작하지만, 미래에 Redis 기반 multi-instance 확장 시 NaverService 같은 도메인 코드를 안 건드리도록 backend 만 swap 가능하게 해두려고 했습니다. SingleFlightCoordinator 라는 인터페이스 (Port) 가 그 추상화입니다.
>
> **Decorator 4개 분리 이유**: 운영 안전망을 SOLID 원칙으로 분리했습니다.
> - Telemetry — 로그/메트릭/waiter count (outermost)
> - Heartbeat — 60초+ 진행 중 entry 조기 감지
> - Capacity — 30+ waiter 거부 (UI spam 보호)
> - Deadline — 180초 wall-clock 타임아웃 (외부 hang 강제 종료)
>
> 각자 단위 테스트 가능하고, env 토글로 끄고 켤 수 있습니다.
>
> **대안 검토**: 단순 mutex (over-locking), per-user lock (외부 호출 N번 여전), Redis 분산 lock (latency overhead + 모든 호출 SPOF), Caffeine.AsyncCache (cache 외 use case 못 다룸) 모두 검토했고 ADR-001~004 에 trade-off 정리돼있습니다."

키 포인트:
- 구체적 architectural 선택 + 이유
- 대안 비교 명시
- 본인이 쓴 ADR 인용 (자료 어필)

### Result (1분) — 결과

> "**기술 임팩트**: 외부 호출이 동시 20요청 시 20번 → 1번으로 감소, 다른 IP 할당이 80초 윈도우에서 10개 → 1개, P99 latency 850ms → 32ms.
>
> **비즈니스 임팩트**: 24시간 동안 발생하던 보안 차단 incident 5건이 0건으로 완전 차단됐습니다. CS 비용 + customer churn 위험 합산 연간 약 N원 손실 회피로 추정했고, 정성적으로는 외부 시스템의 IP 풀 blacklist 위험을 사전에 차단했습니다.
>
> **Portfolio 측면**: 이 패턴을 Java / Spring MVC / WebFlux 로 재구현해서 paradigm 별 thundering herd 표출 차이를 비교하고 있습니다. 진행 중이고 [GitHub repo URL] 에서 확인 가능합니다."

키 포인트:
- 정량 + 정성
- "여기서 멈추지 않고 portfolio 까지" 시그널
- repo 링크 제공

---

## Deep Dive — 자주 받는 꼬리질문 7개

### Q1. "왜 Caffeine.AsyncCache 안 썼나요?"

(Caffeine 의 `AsyncLoadingCache` 는 내장 coalesce 가 있음)

**답변**:
> "ADR-004 에 정리돼있는데, Caffeine.AsyncCache 는 cache 라는 가정 하에 동작합니다. 즉:
> 1. 결과를 보관하는 게 목적 (cache 의 본질)
> 2. TTL 기반 만료
>
> 우리 use case 인 `ensureSession()` 은 cache 가 아니라 **외부 자원 할당**입니다. 결과 보관은 별도 cache 가 이미 함. 우리가 필요한 건 **'동시 호출을 1번으로 합치는 것 자체'** 였고, deadline / capacity / telemetry 같은 운영 안전망까지 통합하기 위해 별도 추상화가 필요했습니다.
>
> 또한 Caffeine 에 묶이면 미래 Redis 기반 multi-instance 로 갈 때 추상화 swap 이 어려워집니다. Port/Adapter 로 분리해두면 backend 만 갈아끼우면 됩니다."

### Q2. "Decorator 순서가 의미 있나요?"

**답변**:
> "ADR-003 에 정리돼있습니다. 순서가 의미 있습니다.
>
> 안쪽부터: Base → Deadline → Capacity → Heartbeat → Telemetry (outermost).
>
> **Telemetry 가 outermost 인 이유**: Deadline decorator 가 throw 하는 `SingleFlightDeadlineException` 을 분류해서 status=deadline 으로 메트릭 찍어야 하기 때문입니다. Telemetry 가 안쪽에 있으면 deadline exception 이 아니라 hang 만 보입니다.
>
> **Capacity 가 Deadline 위인 이유**: capacity 검사는 즉시 reject 인 반면 deadline 은 진행 중인 op 의 timeout. 즉 capacity 가 먼저 check → 통과면 deadline 의 timer 시작.
>
> 순서 바뀌면 분류 / observability 가 부정확해집니다."

### Q3. "Multi-instance 환경에선?"

**답변**:
> "ADR-002 에 정리돼있습니다. 단일 인스턴스 in-process Map 으로 시작했고, 멀티 인스턴스로 갈 때 Redis SETNX + Lua 해제 + pub/sub 결과 broadcast 로 확장합니다.
>
> 처음부터 Redis 안 한 이유:
> 1. 운영 단순성 (Redis 의존 추가는 SPOF 추가)
> 2. 단일 인스턴스 시점에는 latency overhead 3-10ms 가 비용 대비 가치 없음
> 3. Redis 장애 시 fallback 정책이 별도 설계 필요
>
> Multi-instance 로 갈 때 **Coordinator 만 swap**, NaverService 코드는 0 수정. 이게 Hexagonal Port/Adapter 의 가치입니다."

### Q4. "MVC vs WebFlux 어느 게 더 좋나요?"

**답변**:
> "더 좋다 가 아닌 다른 trade-off 입니다.
>
> **MVC**: Tomcat thread pool 보호가 핵심. Thread-per-request 라 동시 N 요청이 N thread 점유. SingleFlight 도입 시 N-1 thread 즉시 free.
>
> **WebFlux**: Connection pool 보호가 핵심. Event loop 는 안 막히지만 WebClient connection pool 폭증. SingleFlight 도입 시 connection 1번만 유지.
>
> 같은 thundering herd 인데 표출되는 자원이 다릅니다. 트래픽 패턴 / downstream 특성 / 팀 역량에 따라 결정. 토스 같이 reactive 헤비한 곳은 WebFlux, 일반 CRUD 헤비한 곳은 MVC.
>
> Portfolio 의 cross-paradigm 챕터에서 두 모델의 측정값을 같이 보여줍니다."

### Q5. "Java 21 Virtual Threads 면 다 해결 아닌가요?"

**답변**:
> "Virtual Threads 는 thread 비용을 cheap 하게 만들지만 **downstream 자원은 비싸다** 는 게 그대로입니다.
>
> 즉:
> - VT 환경에서도 1000 동시 요청 → 1000 outbound connection
> - 외부 시스템은 여전히 distributed login 으로 감지 가능
> - 외부 rate limit / cost 도 여전
>
> SingleFlight 는 'thread' 보호가 본질이 아니라 'downstream' 보호가 본질이라 VT 와 직교적입니다.
>
> v1.1 의 Phase 7 에서 VT demo 추가해서 시연 예정입니다."

### Q6. "Coroutines (Kotlin) 와 비교는?"

**답변**:
> "같은 패턴, 다른 idiom.
>
> Kotlin 의 경우:
> - `Mutex.withLock { ... }` + `Deferred<T>` 로 coalesce 표현
> - `ConcurrentHashMap.computeIfAbsent` + `CompletableFuture` 와 본질 동일
> - 다만 코루틴 컨텍스트에서 자연스럽게 통합
>
> 토스 / 우아한형제들 / 당근 같이 Kotlin + Coroutines 헤비한 곳에선 이 idiom 이 더 익숙합니다. v1.1 Phase 8 에서 demo 추가 예정입니다.
>
> 핵심 통찰: paradigm 이 달라도 본질 (per-key coalesce) 은 같고 idiom 만 다릅니다. THESIS 의 L4-L5 단계입니다."

### Q7. "Deadline 안에 Heartbeat 도 있는데 중복 아닌가요?"

**답변**:
> "역할이 다릅니다.
>
> **Deadline (180초)**: hard wall-clock 타임아웃. 도달 시 owner + 모든 waiter 강제 reject. **사후 차단**.
>
> **Heartbeat (60초)**: 진행 중 entry 의 age 가 60초+ 되면 `long_running` warn 로그 + alert. **사전 감지**.
>
> 즉 60~180초 구간에 heartbeat 가 운영자에게 'stuck 가능성' 알림 → 운영자가 force release 또는 조사 → deadline 도달 전에 수동 개입 가능. Deadline 만 있으면 운영자가 180초 기다리고 사후에야 발견.
>
> 또한 두 메커니즘은 다른 행동:
> - Deadline = 강제 reject (사용자에게 에러)
> - Heartbeat = 관찰만 (사용자 영향 없음)
>
> 같이 있어야 운영 안전망이 완성됩니다."

---

## 추가 꼬리질문 — 비즈니스 측면

### Q. "이 incident 의 비즈니스 임팩트를 어떻게 정량화했나요?"

**답변**:
> "[BENCHMARK.md](BENCHMARK.md) 의 비즈니스 임팩트 framework 에 정리했는데:
>
> 1. **CS 비용**: lock incident 평균 처리 시간 14분 × CS 시급 × 월 incident 추정 → 월 N1 원
> 2. **Customer churn 위험**: lock 후 24h 미접속 비율 P% → 월 잠재 churn M명 × LTV → 월 N2 원
> 3. **정성 위험**: 외부 시스템 IP 풀 blacklist 가능성, 평판 손상, ToS 위반 의심
>
> 합산 연간 회피 가치 N1+N2 × 12 ≈ N 원 추정.
>
> 정확한 N 값은 NDA 영역이지만, **이렇게 정량화하는 사고 자체** 가 시니어 어필 포인트라고 봅니다."

### Q. "이 패턴이 다른 도메인에도 적용 가능한가요?"

**답변**:
> "Single-Flight 는 thundering herd 의 표준 해법입니다. 적용 가능 도메인:
>
> 1. Cache stampede 방지 (Caffeine 도 이걸 함)
> 2. 외부 API rate limit 보호
> 3. 비싼 계산의 동시 실행 방지 (deduplicated computation)
> 4. 분산 시스템의 leader election layer
> 5. DB connection pool 의 connection 획득 race
> 6. Auth token refresh 합치기
>
> 본질은 '동일 key 의 동시 호출을 1번으로 합치는' 보편 패턴이고, Hexagonal + Decorator 구조라 어느 domain 에든 swap 가능합니다."

---

## 안티 패턴 — 면접에서 피할 답변

### ❌ "그냥 SingleFlight 패턴 썼어요"
→ 시니어가 아닌 신호. **왜 그게 필요했고, 어떤 대안과 비교했는지** 말해야.

### ❌ "P99 가 850ms 에서 32ms 로 빨라졌어요"
→ 비즈니스 임팩트 누락. **외부 호출 N→1 + customer lock 차단 + 사업 위험 회피** 까지.

### ❌ "Caffeine 보다 직접 짠 게 더 나아요"
→ NIH 시그널. **Caffeine 검토했고, 우리 use case (cache 아닌 외부 자원 할당) 에 맞는 대안 선택** 으로.

### ❌ "WebFlux 가 Spring MVC 보다 우월합니다"
→ 단편적. **trade-off, 표출되는 자원 차이** 로.

### ❌ "AI 로 짰어요"
→ 도구 자랑. **본인이 architectural 결정 + 비즈니스 사고 했고, AI 는 boilerplate 작성 보조** 로.

---

## 면접 후 — Self-debrief 체크리스트

면접 끝난 후 자가 점검:

- [ ] STAR 4 단계 다 말했나
- [ ] 측정값 4개 (외부 호출 / IP / P99 / 차단) 다 말했나
- [ ] 대안 4개 검토 trade-off 말했나
- [ ] ADR cite 했나 (있는 줄 알게 했나)
- [ ] GitHub repo 링크 제공했나
- [ ] 비즈니스 임팩트 (정량 + 정성) 말했나
- [ ] 일반화 (다른 도메인 적용 가능성) 언급했나
- [ ] AI / 도구 의존 어필 안 했나

---

## Related

- [STORY.md](STORY.md) — 7-act narrative arc
- [INCIDENT.md](INCIDENT.md) — 익명화된 customer story
- [BENCHMARK.md](BENCHMARK.md) — 비즈니스 임팩트 framework
- [adr/](adr/) — 5 ADR (cite 가능)
- [THESIS.md](THESIS.md) — portfolio 진화 thesis (L0 → L5)
