# Single-Flight Coordinator Lab

> 외부 API 연동 시스템에서 동시 요청 coalescing 으로 외부 시스템의 보안 차단을 해결한 패턴.
> Hexagonal Architecture (Port/Adapter) + Decorator Stack 으로 운영 안전망까지 통합.
> **MVC / WebFlux / Virtual Threads / Coroutines 4 paradigm 에서 같은 문제의 표출 방식 비교** 가 진짜 어필 포인트.

[![Status](https://img.shields.io/badge/status-Phase%201%20WIP-yellow)]()
[![Java](https://img.shields.io/badge/Java-21-orange)]()
[![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3.x-brightgreen)]()
[![License](https://img.shields.io/badge/license-MIT-blue)]()

---

## TL;DR

운영 중 외부 API 연동 시스템에서 같은 user 에 대한 동시 요청이 각자 독립 외부 세션을 만들면서 외부 시스템에 "distributed login attempt" 로 감지 → 보안 차단 발동, user 1 시간 lock 사고 발생.

**해결**: Single-Flight Coordinator (Port/Adapter + Decorator stack) 도입. 동일 key 동시 호출을 1 owner + N waiter 로 합침. 외부 호출 1 번만 발생.

**측정 결과** (예상치, Phase 별로 확정):

| Paradigm | 외부 호출 | P99 | 자원 점유 |
|---|---|---|---|
| Spring MVC — Baseline | 100 | 850ms | Tomcat thread 100% |
| Spring MVC — Coalesced | 1 | 32ms | Tomcat thread 5% |
| Spring WebFlux — Baseline | 100 | 720ms | Connection pool 100% |
| Spring WebFlux — Coalesced | 1 | 28ms | Connection pool 1% |

→ **같은 thundering herd 인데 paradigm 마다 다른 자원이 폭주.** 시니어급 깊이.

자세한 학습 목표는 [THESIS.md](docs/THESIS.md).

---

## 왜 이 repo 가 다른 portfolio 와 다른가

### 1. 실제 운영 incident 기반 (synthetic 아님)
- 익명화된 실제 timeline + RCA + 측정값
- 합성 도메인 (쿠폰 / 가격 조회) 어필 한계 회피

### 2. Library + Demo 분리 — framework 와 무관한 core
- `coordinator-core` 는 **Pure Java** (Spring 0)
- `mvc-demo` / `webflux-demo` 는 측정용 wrapper
- "어디든 임베드 가능" 시그널

### 3. Multi-paradigm 비교
- Spring MVC (thread-per-request)
- Spring WebFlux (Reactor event loop)
- (v1.1) Java 21 Virtual Threads
- (v1.1) Kotlin Coroutines
- Cross-language: TypeScript ↔ Java

### 4. 6 단계 진화 모델 (THESIS.md 의 L0~L5)
패턴 이해 → framework 한계 인식 → paradigm 비교 → trade-off 판단 → 통합 통찰

---

## 빠른 시작 (Phase 2 완료 후)

```bash
# 1. 인프라 띄우기
cd infra && docker-compose up -d

# 2. MVC demo 기동
./gradlew :mvc-demo:bootRun

# 3. 부하 테스트
cd benchmark && k6 run k6/mvc-baseline.js
k6 run k6/mvc-coalesced-inprocess.js

# 4. Grafana 시각화
open http://localhost:3000  # admin/admin
```

WebFlux demo (Phase 6 완료 후):
```bash
./gradlew :webflux-demo:bootRun     # 별도 port
k6 run k6/webflux-baseline.js
k6 run k6/webflux-coalesced.js
```

---

## 디렉토리 구조

```
single-flight-coordinator-lab/
│
├── coordinator-core/          ★ Pure Java 라이브러리 (Spring 0)
│   └── src/main/java/com/portfolio/singleflight/
│       └── coordinator/
│           ├── (port — sync + reactive)
│           ├── adapter/       in-process / redis
│           ├── decorator/     telemetry / heartbeat / capacity / deadline
│           └── exception/
│
├── mvc-demo/                  Spring MVC + Tomcat (Phase 2)
│   └── thread pool 통점 demo
│
├── webflux-demo/              Spring WebFlux + Reactor (Phase 6)
│   └── event loop / connection 통점 demo
│
├── reference/ts-impl/         TypeScript 참조 구현 (Phase 4, 회사 코드 아님)
│
├── infra/                     docker-compose: app + redis + prometheus + grafana
├── benchmark/                 k6 부하 테스트 + 결과 archive
└── docs/
    ├── THESIS.md              ★ portfolio 의 진화 thesis (L0 → L5)
    ├── INCIDENT.md            익명화된 실제 incident
    ├── DESIGN.md              architecture 다이어그램
    ├── BENCHMARK.md           Before/After 측정 결과
    ├── INTERVIEW.md           1분/5분/딥다이브 답변 스크립트
    ├── adr/                   ADR-001 ~ ADR-005
    └── cross-paradigm/        MVC vs WebFlux 등 비교 챕터
```

---

## 6-Phase Roadmap

| Phase | 제목 | Issue | Milestone | 상태 |
|---|---|---|---|---|
| 1 | coordinator-core (Pure Java, no Spring) | [#2](https://github.com/PreAgile/single-flight-coordinator-lab/issues/2) | v0.1 | 🔜 |
| 2 | mvc-demo (Tomcat thread pool 통점) | [#3](https://github.com/PreAgile/single-flight-coordinator-lab/issues/3) | v0.5 | 🔜 |
| 3 | redis-adapter (multi-instance) | [#4](https://github.com/PreAgile/single-flight-coordinator-lab/issues/4) | v0.6 | 🔜 |
| 4 | TS reference + cross-language | [#5](https://github.com/PreAgile/single-flight-coordinator-lab/issues/5) | v0.7 | 🔜 |
| 6 | webflux-demo (event loop 통점) | [#7](https://github.com/PreAgile/single-flight-coordinator-lab/issues/7) | v0.9 | 🔜 |
| 5 | polish + portfolio integration | [#6](https://github.com/PreAgile/single-flight-coordinator-lab/issues/6) | v1.0 | 🔜 |

진행 추적: [Issues](https://github.com/PreAgile/single-flight-coordinator-lab/issues) · [Milestones](https://github.com/PreAgile/single-flight-coordinator-lab/milestones)

### v1.1 확장 (면접 본격화 시점)
- Phase 7: virtual-threads-demo (Java 21 Loom)
- Phase 8: coroutines-demo (Kotlin)

---

## 주요 문서

| 문서 | 설명 |
|---|---|
| [docs/THESIS.md](docs/THESIS.md) | ★ 이 repo 의 학습 목표 — L0 → L5 진화 모델 |
| [docs/INCIDENT.md](docs/INCIDENT.md) | 익명화된 실제 운영 incident |
| [docs/DESIGN.md](docs/DESIGN.md) | Port/Adapter + Decorator 아키텍처 |
| [docs/BENCHMARK.md](docs/BENCHMARK.md) | Before/After 측정 방법론 + 결과 |
| [docs/INTERVIEW.md](docs/INTERVIEW.md) | 1분 / 5분 / 딥다이브 답변 스크립트 |
| [docs/cross-paradigm/mvc-vs-webflux.md](docs/cross-paradigm/mvc-vs-webflux.md) | MVC vs WebFlux 통점 비교 |

## ADR (Architecture Decision Records)

| ADR | 주제 | 결정 |
|---|---|---|
| [ADR-001](docs/adr/ADR-001-port-adapter-pattern.md) | Port/Adapter 채택 | framework 와 무관한 core 위해 |
| [ADR-002](docs/adr/ADR-002-in-process-vs-redis-lock.md) | In-process vs Redis | 단일→멀티 단계적 도입 |
| [ADR-003](docs/adr/ADR-003-decorator-stack-order.md) | Decorator 순서 | Telemetry outermost (분류 정확성) |
| [ADR-004](docs/adr/ADR-004-coordinator-vs-caffeine.md) | Caffeine.AsyncCache 와 비교 | 운영 안전망 통합 위해 직접 |
| [ADR-005](docs/adr/ADR-005-reactive-vs-sync-coalescing.md) | Reactive vs sync coalescing | Sinks.One 기반 reactive idiom |

---

## 기술 스택

**Library (coordinator-core)**: Java 21 · JUnit 5 · AssertJ · Awaitility · Micrometer-core
**MVC Demo**: Spring Boot 3.x · Tomcat · Spring Web · Actuator
**WebFlux Demo**: Spring Boot 3.x · Spring WebFlux · Reactor · Sinks.One
**Multi-instance**: Redis 7 · Redisson · Testcontainers
**측정**: Prometheus · Grafana · k6
**Cross-language reference**: TypeScript 5 · Jest

---

## 면접 어필 narrative

상세 스크립트는 [docs/INTERVIEW.md](docs/INTERVIEW.md). 1 분 버전:

> "B2B SaaS 외부 API 연동 시스템 운영 중에, 같은 user 의 동시 요청이 외부 시스템에 distributed login 으로 감지되어 보안 차단 걸리는 incident 를 겪었습니다. ensureSession 에 per-user 직렬화가 없는 게 root cause 였고, Single-Flight Coordinator 를 Hexagonal + Decorator (Telemetry / Heartbeat / Capacity / Deadline) 로 도입해서 풀었습니다.
>
> 이 repo 는 그 패턴을 Java 21 / Spring MVC / WebFlux 로 재구현하면서 각 paradigm 의 thundering herd 표출 방식을 측정값으로 비교한 portfolio 입니다. 핵심 통찰은 — paradigm 이 달라도 본질은 같고 idiom 만 다르다는 것이고, 새 framework (Loom, Coroutines) 만나도 같은 사고로 적응할 수 있다는 게 시니어 시그널이라 생각합니다."

---

## NDA / 컴플라이언스

- 회사명 / 외부 플랫폼명 / 운영 디테일 (정확한 시각, 내부 metric 이름) 노출 금지
- 패턴 + 추상화된 timeline + 측정값 까지만 공개
- 모든 코드 본 repo 에서 새로 작성 (회사 코드 1:1 복붙 X)
- 면접에서 디테일 질문 시: "비공개 NDA 라 답 못 드립니다. 단 패턴은 일반화 가능합니다." 명확히 답변

---

## 관련 프로젝트

- 자매 repo (계획): concurrency-cache-lab — Cache Stampede 일반화
- 설계 허브: portfolio-docs — 6 Deep Dive Track 전체 전략

## License

MIT — see [LICENSE](LICENSE).
