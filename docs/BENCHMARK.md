# BENCHMARK — Methodology, Results, and Business Impact Framework

> 측정값은 portfolio 의 무기. 이 문서는 **재현 가능한 측정 방법론** + **paradigm 별 결과** + **비즈니스 임팩트 정량화 framework**.
>
> Phase 별 결과는 측정 완료 시점에 채워짐. 현재 일부는 placeholder.

---

## Why Measurement-Driven

Portfolio 의 핵심 원칙 ([THESIS.md](THESIS.md) 참고):

> ❌ "고성능 시스템 만들었어요"
> ✅ "k6 기준 100 동시 요청 시 외부 호출 100→1, P99 850ms→32ms, Tomcat busy thread 100%→5%"

**측정 가능한 수치만 어필.** 그게 portfolio 가 다른 portfolio 와 차이를 만드는 점.

---

## 공통 벤치마크 규약

모든 Phase 의 측정에 적용. portfolio-docs 의 "공통 벤치마크 규약" 과 일관.

| 항목 | 규약 | 이유 |
|---|---|---|
| Warm-up | 측정 전 30초 warm-up, 결과에서 제외 | JIT 컴파일 / 커넥션 풀 / 캐시 hit 안정화 |
| 측정 시간 | 최소 60초 sustained load | 짧은 burst 는 GC, scheduler 노이즈 반영 못함 |
| 반복 | 동일 조건 3회 실행, 중앙값 사용 | 단발 측정은 outlier 취약 |
| 데이터셋 | 트랙별 고정 seed (크기 명시) | 데이터 양에 따라 성능 변화 |
| 환경 스펙 | docker-compose 리소스 제한값 명시 (CPU/Memory) | "내 맥북에서 돌린 숫자" 에 재현성 부여 |
| 성공 기준 | 에러율 < 1%, P99 < 목표값 | 높은 TPS 지만 에러율 10% 면 무의미 |
| 기록 | 환경 + 조건 + 수치 + Grafana 캡처 | 면접에서 "어떤 환경?" 즉답 |

---

## Test Environment

### Hardware (현재 spec — Phase 별 측정 시 갱신)

```
Host:        Apple Mac M1 / M2 (24GB RAM)
Docker:      6 CPU / 12GB RAM allocated
JVM:         OpenJDK 21 (Temurin) / -Xmx512m / -Xms256m / -XX:+UseG1GC
Spring:      Boot 3.2+
Tomcat:      max-threads=200 (default)
```

### Software Stack

```
Scrap layer:   k6 v0.x (HTTP load)
Metric collector: Micrometer + Prometheus 2.x
Visualization: Grafana 10.x
Backing store: Redis 7.2 (Phase 3+)
External API simulator: SlowExternalApi (in-process, 500ms 인위 지연)
```

### Workload Profile

```
요청 패턴: GET /api/{baseline|coalesced}/expensive?key={K}
key 분포: 단일 key (worst case for thundering herd)
부하: VU=100 (k6 virtual users)
패턴: warm-up 30s + sustained 60s + cool-down 30s
반복: 3 회, 중앙값 사용
외부 API 시뮬: 500ms 지연 + 90% 성공률
```

---

## Phase 1 Results — Library Unit Tests

> 단위 테스트 차원의 정확성 검증. 부하 측정은 Phase 2 부터.

### Coverage

```
coordinator-core/src/test/
├── adapter/InProcessSingleFlightCoordinatorTest    [ 7 tests ]
├── decorator/DeadlineDecoratorTest                  [ 4 tests ]
├── decorator/CapacityDecoratorTest                  [ 4 tests ]
├── decorator/HeartbeatDecoratorTest                 [ 3 tests ]
├── decorator/TelemetryDecoratorTest                 [ 5 tests ]
└── integration/FullChainTest                        [ 4 tests ]
                                                    Total: 27 tests
```

### Performance (단위 테스트 환경)

```
TBD — Phase 1 완료 후 채움
```

---

## Phase 2 Results — MVC Demo (Tomcat)

> MVC paradigm 의 thundering herd 표출 + SingleFlight 효과 측정. **"thread pool 보호"** 가 핵심.

### Scenario

```
Endpoints:
  GET /api/baseline/expensive?key=K   (no coalesce)
  GET /api/coalesced/expensive?key=K  (with single-flight)

Workload: 100 VU, 동일 key K, sustained 60s
External API: 500ms 지연
```

### Expected Results (Phase 2 완료 시 검증)

| Metric | Baseline | Coalesced | Δ |
|---|---|---|---|
| 외부 호출 수 (60s 동안) | ~6,000 | 1 | **6000×** |
| P50 latency | 500ms | 5ms | 100× |
| P99 latency | 850ms | 32ms | **26×** |
| Error rate | < 1% | < 1% | — |
| Tomcat busy thread (avg) | 100% | 5% | **20× free** |
| Throughput (req/s) | ~120 | ~3,000 | 25× |

### MVC 의 통점 시각화 (Phase 2 완료 시 Grafana 캡처 첨부)

```
[ Baseline ]
Tomcat busy thread:  ████████████████████  100% (200/200)
External API calls:  ▲▲▲▲▲▲▲▲▲▲▲▲▲▲▲▲▲▲   sustained 100/sec
P99:                 ━━━━━━━━━━━━━━━━━━━   850ms
HTTP 503:            ▒▒▒▒                  burst on saturation

[ Coalesced ]
Tomcat busy thread:  █                     5% (10/200)
External API calls:  ▲                     1 (only owner)
P99:                 ▔                     32ms
HTTP 503:            (none)
```

→ **MVC paradigm 에선 thread pool 이 자원 병목. SingleFlight 가 thread pool 을 자유롭게 함.**

---

## Phase 3 Results — Multi-instance with Redis

> Multi-instance 환경에서 cross-process coalesce. **"분산 차원에서도 1번"**.

### Scenario

```
Setup: 2 인스턴스 (mvc-demo-1, mvc-demo-2) behind nginx round-robin
Backend: Redis SETNX + Pub/Sub
Workload: 100 VU, 동일 key K, sustained 60s
```

### Expected Results

| Metric | In-process (단일) | In-process (2 인스턴스) | Redis (2 인스턴스) |
|---|---|---|---|
| 외부 호출 수 | 1 | 2 (인스턴스별 race) | **1** |
| P99 latency | 32ms | 32ms | 38ms (Redis RTT) |
| Cross-instance coalesce | N/A | ❌ | ✅ |
| Redis 의존성 | 없음 | 없음 | 있음 (SPOF) |

→ **In-process Map 만으로는 multi-instance 에서 race 발생. Redis layer 필요.**

상세 trade-off: [adr/ADR-002-in-process-vs-redis-lock.md](adr/ADR-002-in-process-vs-redis-lock.md)

---

## Phase 6 Results — WebFlux Demo (Reactor)

> Reactive paradigm 의 thundering herd 표출. **"connection pool 보호"** 가 핵심.

### Scenario

```
Endpoints:
  GET /api/flux/baseline/expensive?key=K
  GET /api/flux/coalesced/expensive?key=K

Workload: 100 VU, 동일 key K, sustained 60s
External: WebClient (non-blocking) 500ms 지연
```

### Expected Results

| Metric | Baseline | Coalesced | Δ |
|---|---|---|---|
| 외부 호출 수 | ~6,000 | 1 | 6000× |
| P50 latency | 500ms | 4ms | 125× |
| P99 latency | 720ms | 28ms | **26×** |
| WebClient connection (peak) | 100 | 1 | 100× |
| Reactor scheduler pending | low | low | — |
| Throughput (req/s) | ~140 | ~3,500 | 25× |

### WebFlux 통점 vs MVC 통점 비교

| 자원 | MVC 표출 | WebFlux 표출 |
|---|---|---|
| Thread (worker) | **포화** ★ | 안 막힘 |
| HTTP connection | (간접) | **포화** ★ |
| Event loop | N/A | OK |
| Memory | OK | OK |
| GC | normal | normal |

→ **같은 thundering herd 인데 서로 다른 자원이 폭주.** 시니어급 깊이.

상세 비교: [cross-paradigm/mvc-vs-webflux.md](cross-paradigm/mvc-vs-webflux.md)

---

## Business Impact Framework

> 기술 메트릭은 시작. **비즈니스 임팩트 정량화** 가 시니어 시그널.

### 정량 임팩트 — 4 차원

#### 1. Customer Support 비용

```
변수:
  T_lock      = 평균 lock incident 처리 시간 (분, 실측)
  N_monthly   = 월간 incident 추정 (실측 × 30)
  R_cs        = CS 시급 (시장 평균, 회사 정책)

월 CS 비용 = (T_lock / 60) × N_monthly × R_cs
연간 CS 비용 회피 = 월 CS 비용 × 12
```

이 incident 적용:
- T_lock = 14분
- N_monthly ≈ 150건 (24h 5건 × 30)
- R_cs = X 원 (NDA)
- → 월 CS 회피 ≈ N1 원, 연간 ≈ N1 × 12 원

#### 2. Customer Churn 위험

```
변수:
  P_churn    = lock incident 후 24h 미접속 비율 (실측)
  N_monthly  = 월 incident 수 (위와 동일)
  LTV        = 1인당 평균 매출 (배포 / sub 모델 기준)

월 잠재 churn = P_churn × N_monthly
월 매출 손실 위험 = 월 잠재 churn × LTV
```

이 incident 적용:
- P_churn ≈ 5% (lock 후 24h 미접속)
- N_monthly = 150
- 월 잠재 churn = 7.5명
- 연간 손실 위험 ≈ 90 × LTV

#### 3. 평판 / 신뢰 손상

```
정성:
- "새로운 환경 로그인" 메일 5통 → customer 의심 + 비밀번호 변경
- 변경된 비밀번호 다음 날까지 우리 SaaS 신뢰 손상
- Customer A 같은 가맹점주 사이 입소문 (negative WOM)
- 월 영향 customer × 0.X (입소문 multiplier) = 추가 잠재 영향
```

#### 4. Compliance / Service Continuity 위험

```
극단 시나리오:
- 외부 시스템이 차단 정책 강화 → 1시간 → 24시간
- 누적 차단 → 외부 시스템에서 우리 IP 풀 전체 blacklist
- → 서비스 전면 중단 시나리오

확률 × 영향 정량화:
  P_blacklist = 6개월 내 blacklist 발생 확률 (NDA, 정성 추정)
  Service downtime cost = 시간당 매출 × 평균 복구 시간
  → 6개월 위험 = P_blacklist × downtime cost
```

### 합산 회피 가치

```
연간 회피 ≈ (CS 비용) + (churn 위험) + (평판 multiplier) + (compliance 위험)
        ≈ 약 N 원 (NDA, 자릿수만 노출 가능)
```

→ **단순 기술 개선이 아니라 사업 위험 회피.** 면접 어필 핵심.

### 정량화의 portfolio 가치

이 framework 자체가 어필 자산:
- 면접에서 "비즈니스 임팩트 어떻게 측정?" 물으면 위 framework 인용
- 실 N 값은 NDA, 사고 방식은 일반화 가능

> ❌ "lock 이 막혔으니 좋은 일"
> ✅ "월 CS 비용 N1 + 잠재 churn N2 + 평판 손상 + compliance 위험 합산 연간 약 N 원 회피로 추정"

---

## Reproducing the Benchmark

본 repo 의 portfolio 측정은 **누구나 재현 가능**.

```bash
# 1. 인프라
cd infra && docker-compose up -d

# 2. MVC Phase 측정
cd ../benchmark
bash scripts/run-mvc.sh

# 3. WebFlux Phase 측정
bash scripts/run-webflux.sh

# 4. Multi-instance Phase 측정
bash scripts/run-redis-multi.sh

# 5. 결과 비교
open results/<날짜>/comparison.md
```

→ 면접관이 본인 환경에서도 같은 측정 가능. 신뢰성 시그널.

---

## Open Questions / TODO

- [ ] Phase 1: 단위 테스트 시간 측정 (microbenchmark)
- [ ] Phase 2: 실제 측정값 채우기 + Grafana 캡처
- [ ] Phase 2: GC pressure 비교 (Baseline vs Coalesced)
- [ ] Phase 3: Redis pub/sub vs polling 결과 전달 비교
- [ ] Phase 6: Reactor 의 backpressure overflow 시나리오 측정
- [ ] Phase 6: Mono.cache() vs Sinks.One coalesce 비교
- [ ] (확장) Phase 7: Loom VT 환경에서 thundering herd 표출
- [ ] (확장) Phase 8: Coroutines 환경에서 같은 측정

---

## Related

- [STORY.md](STORY.md) — narrative arc
- [INCIDENT.md](INCIDENT.md) — Customer A timeline
- [DESIGN.md](DESIGN.md) — Hexagonal + Decorator 설계
- [INTERVIEW.md](INTERVIEW.md) — 답변 스크립트 (비즈니스 임팩트 답변 cite)
- portfolio-docs/STRATEGY.md — 공통 벤치마크 규약
