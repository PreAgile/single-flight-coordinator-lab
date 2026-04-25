# Single-Flight Coordinator Lab

> 외부 API 연동 시스템에서 동시 요청 coalescing 으로 외부 시스템의 보안 차단을 해결한 패턴.
> Hexagonal Architecture (Port/Adapter) + Decorator Stack 으로 운영 안전망까지 통합.

[![Status](https://img.shields.io/badge/status-Phase%201%20WIP-yellow)]()
[![Java](https://img.shields.io/badge/Java-21-orange)]()
[![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3.x-brightgreen)]()
[![License](https://img.shields.io/badge/license-MIT-blue)]()

---

## TL;DR

운영 중 외부 API 연동 시스템에서 같은 user 에 대한 동시 요청이 각자 독립 외부 세션을 만들면서 외부 시스템에 "distributed login attempt" 로 감지 → 보안 차단 발동, user 1시간 lock 사고 발생.

**해결**: Single-Flight Coordinator 를 도입해서 동일 key 에 대한 동시 호출을 1 owner + N waiter 로 합침. 외부 호출 1번만 발생.

**측정 결과** (예상치, Phase 2 에서 확정):

| 시나리오 | 외부 호출 수 | P99 | 보안 차단 |
|---|---|---|---|
| Baseline (no coalesce) | 100 (동시 100 요청) | 850ms | 발생 |
| In-process coalesce | 1 | 32ms | 없음 |
| Redis coalesce (멀티 인스턴스) | 1 | 38ms | 없음 |

---

## 왜 이 패턴이 흥미로운가

이 코드베이스는 한 가지 운영 incident 를 다음 4가지 기술적 axis 로 깊이 다룬다:

1. **분산 시스템 패턴** — Single-Flight (Go 의 singleflight 패키지로 유명), thundering herd 방지
2. **Hexagonal Architecture** — Port/Adapter 로 in-process ↔ Redis ↔ 미래 MQ-routing backend 교체 가능
3. **Decorator Pattern (GoF)** — Telemetry / Heartbeat / Capacity / Deadline 을 SOLID 원칙으로 stack
4. **Cross-language 비교** — TypeScript Promise 모델 vs Java CompletableFuture 모델 (`reference/ts-impl/` 참고)

---

## 빠른 시작 (Phase 2 완료 후)

```bash
# 1. 인프라 띄우기 (Spring Boot + Redis + Prometheus + Grafana)
cd infra && docker-compose up -d

# 2. 앱 실행
cd ../app && ./gradlew bootRun

# 3. 부하 테스트
cd ../benchmark && k6 run k6/baseline-thundering-herd.js
k6 run k6/coalesced-inprocess.js

# 4. Grafana 에서 시각화
open http://localhost:3000  # admin/admin
```

---

## 구조

```
single-flight-coordinator-lab/
├── README.md              ← 이 파일
├── docs/                  ← Narrative, ADR, 비교 챕터
├── app/                   ← Java 21 + Spring Boot 3.x 구현
│   └── src/main/java/com/portfolio/singleflight/
│       └── coordinator/
│           ├── (port)
│           ├── adapter/   ← InProcess / Redis
│           └── decorator/ ← Telemetry / Heartbeat / Capacity / Deadline
├── infra/                 ← docker-compose 측정 스택
├── benchmark/             ← k6 + 결과 archive
└── reference/ts-impl/     ← TS 참조 구현 (회사 코드 아님)
```

---

## 5-Phase Roadmap

| Phase | 제목 | 상태 |
|---|---|---|
| 1 | Skeleton + Core Java implementation | 🔜 |
| 2 | Measurement infrastructure (docker-compose + k6) | 🔜 |
| 3 | Redis adapter (multi-instance coalescing) | 🔜 |
| 4 | TS reference + cross-language comparison | 🔜 |
| 5 | Polish + CI + diagrams | 🔜 |

각 Phase 는 GitHub Issues 로 추적. → [Issues](https://github.com/PreAgile/single-flight-coordinator-lab/issues)

---

## 주요 문서

| 문서 | 설명 |
|---|---|
| [docs/INCIDENT.md](docs/INCIDENT.md) | 익명화된 실제 운영 incident |
| [docs/DESIGN.md](docs/DESIGN.md) | Port/Adapter + Decorator 아키텍처 |
| [docs/BENCHMARK.md](docs/BENCHMARK.md) | Before/After 측정 방법론 + 결과 |
| [docs/INTERVIEW.md](docs/INTERVIEW.md) | 1분 / 5분 / 딥다이브 답변 스크립트 |
| [docs/cross-language/ts-vs-java.md](docs/cross-language/ts-vs-java.md) | TS Promise vs Java CompletableFuture |

## ADR (Architecture Decision Records)

| ADR | 주제 | 결정 |
|---|---|---|
| [ADR-001](docs/adr/ADR-001-port-adapter-pattern.md) | Port/Adapter 패턴 채택 | 미래 backend swap 위해 |
| [ADR-002](docs/adr/ADR-002-in-process-vs-redis-lock.md) | In-process vs Redis lock | 단일→멀티 단계적 도입 |
| [ADR-003](docs/adr/ADR-003-decorator-stack-order.md) | Decorator 순서 | Telemetry outermost |
| [ADR-004](docs/adr/ADR-004-coordinator-vs-caffeine.md) | Caffeine.AsyncCache 와 비교 | 운영 안전망 통합 위해 직접 |

---

## 기술 스택

**런타임**: Java 21 (Virtual Threads) · Spring Boot 3.x · Redis 7 · Redisson
**측정**: Prometheus · Grafana · k6 · Micrometer
**테스트**: JUnit 5 · Testcontainers · AssertJ · Awaitility
**참조 구현**: TypeScript 5 · Jest

---

## 면접 어필 포인트

이 repo 가 보여주는 것들:

1. **실제 운영 경험** — synthetic 도메인이 아닌 실제 incident 기반
2. **측정 기반 의사결정** — Before/After 수치로 검증
3. **Hexagonal Architecture** — Port/Adapter 로 backend 교체 가능
4. **Decorator 패턴 (SOLID)** — 4 책임 분리, 합성 자유도
5. **Cross-language 깊이** — TS Promise 모델 ↔ Java Virtual Threads 모델 비교
6. **분산 시스템 trade-off** — in-process (latency) vs Redis (consistency)
7. **운영 안전망** — Deadline / Capacity / Heartbeat / Telemetry 통합
8. **테스트 격리** — 각 decorator 단위 테스트 + 5 layer 통합 테스트 분리

---

## 관련 프로젝트

- **자매 repo (계획 중)**: concurrency-cache-lab — Cache Stampede 일반화
- **설계 허브**: portfolio-docs — 6 Deep Dive Track 전체 전략

---

## License

MIT — see [LICENSE](LICENSE).
