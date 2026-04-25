# THESIS — 이 Repo 의 진짜 학습 목표

> **"패턴을 안다" 가 아니라, "각 framework / paradigm 의 본질적 통점을 이해하고
> 그 위에서 architectural 적응을 결정할 수 있다" 를 증명하는 것.**

---

## 6 단계 진화 모델 (L0 → L5)

시니어 백엔드 엔지니어의 능력은 단계적이다. 이 repo 는 한 가지 패턴 (Single-Flight) 을 도구로 그 단계를 시연한다.

| 단계 | 능력 | 시그널 |
|---|---|---|
| **L0** | 패턴 자체를 이해 | "Single-flight 가 무엇인가" 설명 가능 |
| **L1** | 한 framework 에서 구현 | "Java 에서 어떻게 푸나" 코드로 증명 |
| **L2** | **그 framework 의 본질적 한계 인식** | "MVC 의 thread-per-request 가 thundering herd 와 어떻게 충돌하나" |
| **L3** | **다른 paradigm 과 비교** | "WebFlux 에선 같은 문제가 connection 폭주로 표출, 다른 idiom 으로 적응 필요" |
| **L4** | **stack trade-off 판단** | "이 트래픽 / 이 SLA 엔 MVC, 저긴 WebFlux, 또 저긴 Coroutines" |
| **L5** | **통합 통찰** | "어떤 stack 이든 thundering herd 는 발생, 본질은 같고 idiom 만 다르다. 새 framework 만나도 적응 가능." |

**주니어 ~ 미들** 은 L0~L1 에서 멈춘다. **"Spring MVC 써봤어요"** 가 끝.

**시니어** 는 L4~L5 까지. **"5 년 뒤 Spring 이 사라져도 본질은 그대로라 적응 가능합니다"** 가 답.

---

## 이 repo 가 시연하는 것 — Phase ↔ Level 매핑

```
v1.0 (현재 진행)
├── Phase 1 (#2)   coordinator-core — Pure Java       → L0, L1
├── Phase 2 (#3)   mvc-demo — Tomcat thread pool 통점    → L2 (MVC 한계)
├── Phase 3 (#4)   redis-adapter — multi-instance       → L1.5 (분산 차원 추가)
├── Phase 4 (#5)   ts-reference + cross-language         → L3 (다른 언어 비교)
├── Phase 6 (#7)   webflux-demo — event loop 통점         → L3 (다른 framework 비교)
└── Phase 5 (#6)   polish + portfolio integration        → 모든 단계 정리

v1.1 (미래 확장)
├── Phase 7        virtual-threads-demo — Loom 통점      → L4 (paradigm 추가)
├── Phase 8        coroutines-demo — Kotlin idiom        → L4 (Kotlin 추가)
└── docs/EVOLUTION.md  통합 통찰                          → L5 (메타)
```

각 Phase 끝에서 사용자는 "이 framework 의 본질을 한 단계 깊이 알게 됐다" 가 남는다.

---

## 왜 이 thesis 가 빅테크 면접 시그널인가

면접관이 시니어 채용에서 진짜 보는 것:

> "5 년 후 회사가 WebFlux 에서 Loom 으로 갈아탈 수 있다.
> Kotlin 으로 갈 수 있다. 그때 'Spring MVC 만 써봤어요' 는 답이 안 된다.
> **새 framework 만나도 본질을 잡고 적응할 수 있는 사람** 인가?"

이 repo 가 보여주는 답:

> "thundering herd 는 어느 model 에서든 같은 문제고, 해법 패턴은 같은데
> 구현 idiom 만 다릅니다. 4 paradigm 에서 같은 문제를 풀어본 비교가 있고,
> 새 stack 에도 같은 사고로 적응 가능합니다."

이게 portfolio 의 **closing argument**.

---

## 면접 narrative — 1 분 / 5 분 / 딥다이브

### 1 분 버전 (자기소개 끝, 프로젝트 한 줄)

> "B2B SaaS 외부 API 연동 시스템에서 동시 요청이 외부 시스템에 distributed login 으로 감지되어 보안 차단 걸리는 incident 를 겪었습니다. SingleFlight 패턴을 Hexagonal + Decorator 로 도입해서 풀었고, 그 패턴을 Java / Spring MVC / WebFlux 로 재구현하면서 각 paradigm 의 thundering herd 표출 방식을 측정값으로 비교한 portfolio piece 입니다."

### 5 분 버전 (이 프로젝트 설명해주세요)

> 1. **Incident** — 동시 20 요청, 80초 안에 10개 IP 로 같은 user 로그인 → 외부 보안 차단
> 2. **RCA** — ensureSession() 에 per-user 직렬화 부재
> 3. **해결** — Single-Flight Coordinator (Port/Adapter + Decorator)
> 4. **재구현** — Java / Spring MVC / WebFlux 로 paradigm 비교
> 5. **측정** — 외부 호출 N → 1, P99 850ms → 32ms (MVC 기준)
> 6. **통찰** — paradigm 별 thundering herd 표출 차이 (thread vs connection)

### 딥다이브 (꼬리질문 방어)

| 질문 | 답 |
|---|---|
| "왜 Caffeine.AsyncCache 안 썼나요?" | ADR-004 — Cache 가 아닌 일반 비동기 작업도 coalesce 필요 + Deadline / Capacity / Telemetry 통합 제어 위해 |
| "Decorator 순서가 의미 있나요?" | ADR-003 — Telemetry outermost 라야 Deadline 분류 정확. Capacity 가 inflight 조회 후 reject |
| "MVC vs WebFlux 어느 게 더 좋나요?" | "더 좋다" 가 아닌 "다른 trade-off". MVC 는 thread pool 보호, WebFlux 는 connection pool 보호. 트래픽 패턴과 downstream 특성에 따라 결정 |
| "Multi-instance 에선?" | ADR-002 — 단일 인스턴스는 in-process Map (latency 0.1ms), 멀티는 Redis SETNX + pub/sub (3-10ms 추가). 단계적 도입 |
| "Java 21 Virtual Threads 면 다 해결 아닌가요?" | VT 가 cheap 해도 downstream connection 은 비싸다. Coalescing 은 여전히 필요. v1.1 의 Phase 7 에서 시연 예정 |
| "Coroutines 와 비교는?" | 같은 패턴, 다른 idiom. `Mutex.withLock` + `Deferred`. v1.1 의 Phase 8 에서 시연 예정 |

---

## 이 repo 의 "안티 패턴" — 무엇을 안 했나

### ❌ 합성 도메인 (synthetic, weak)
"쿠폰 / 가격 조회 만들어서 stampede 막았어요" — 면접관: "그래서 실무에선 어떻게 쓰셨어요?" 답 못 함.

### ✅ 우리 방식 (real, abstracted)
실제 운영 incident 의 timeline / RCA / 측정값을 **익명화된 형태로** 공유. 패턴은 일반화 가능. NDA 디테일은 명시적으로 비공개.

### ❌ 회사 코드 1:1 복붙
git log / commit hash 추적되어 IP 분쟁 위험.

### ✅ 우리 방식
모든 코드 본 repo 에서 새로 작성. TS reference 도 회사 코드 무관한 새 작성. 본질 패턴만 같음.

### ❌ "Spring 잘 써봤어요" 시그널
대다수 portfolio 가 멈추는 곳. CRUD 앱 한 두 개 시연.

### ✅ 우리 방식
Spring 의 본질적 한계 (thread-per-request 와 thundering herd 충돌) 를 측정값으로 시연 + 다른 paradigm 비교.

---

## 면접관에게 보내는 시그널 — 이 repo 의 "first impression"

```
README → BENCHMARK → INTERVIEW → THESIS 순서로 5 분 안에 다음이 전달돼야 함:

1. 실제 운영 문제 해결 경험 있음 (synthetic 아님)
2. 측정 기반 의사결정 함 (수치로 증명)
3. Architectural pattern 이해 (Hexagonal + Decorator)
4. paradigm 별 trade-off 인지 (MVC / WebFlux / VT / Coroutines)
5. 다른 언어 비교 가능 (TS ↔ Java)
6. NDA / 컴플라이언스 의식 (NDA 안에서 어필 방식 명확)
```

이 6 개가 첫 5 분에 전달되면 면접 흐름이 완전히 다르게 흘러간다.

---

## 평가 기준 — 이 repo 가 v1.0 일 때

자가 점검:

- [ ] 5 분 안에 다른 사람에게 README + BENCHMARK 가 자명하게 읽히는가?
- [ ] INTERVIEW.md 의 답변을 본인 입으로 막힘없이 가능한가?
- [ ] "왜 Caffeine 안 썼나" 같은 꼬리질문에 ADR 인용해서 답할 수 있는가?
- [ ] MVC 와 WebFlux 의 thundering herd 표출 차이를 화이트보드에 그릴 수 있는가?
- [ ] 측정값을 외울 수 있는가? (P99, 외부 호출 수, Tomcat occupancy)
- [ ] 5 년 뒤 새 framework 만났을 때 같은 사고로 풀 수 있다고 자신 있게 말할 수 있는가?

마지막 항목이 이 portfolio 의 진짜 시그널이고, **그게 시니어다.**

---

## 관련 자원

- [README.md](../README.md) — Repo overview
- [docs/INCIDENT.md](INCIDENT.md) — 익명화된 실제 incident
- [docs/DESIGN.md](DESIGN.md) — Hexagonal + Decorator 설계
- [docs/BENCHMARK.md](BENCHMARK.md) — Before/After 측정
- [docs/INTERVIEW.md](INTERVIEW.md) — 면접 답변 스크립트
- [docs/cross-paradigm/](cross-paradigm/) — paradigm 비교 챕터들
- [docs/adr/](adr/) — Architecture Decision Records
- [GitHub Issues](https://github.com/PreAgile/single-flight-coordinator-lab/issues) — 6 Phase 진행 상황
