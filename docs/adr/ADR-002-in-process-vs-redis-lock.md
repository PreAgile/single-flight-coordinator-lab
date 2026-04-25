# ADR-002 — In-Process vs Redis Lock for Multi-Instance Coalescing

| Field | Value |
|---|---|
| Status | **📅 Planned** (Phase 3 시작 시 작성) |
| Date | TBD |
| Phase | 3 |
| Deciders | @PreAgile |
| Related | [ADR-001](ADR-001-port-adapter-pattern.md), [ADR-003](ADR-003-decorator-stack-order.md) |

---

## Status: Planned

이 ADR 은 **placeholder** 입니다. Phase 3 (Redis Adapter) 시작 시 실제 결정 + 측정값으로 채워집니다.

현재 시점의 가설 / 검토 dimension 만 outline.

---

## Planned Context

Phase 1 의 InProcess adapter 는 단일 JVM 한정. 멀티 인스턴스 환경 (K8s scale-out, rolling deploy) 에서 cross-instance coalesce 필요.

## Planned Decision Dimensions

채택 시 평가할 7 axis:

| Dimension | InProcess | Redis | 결정 영향 |
|---|---|---|---|
| Latency overhead | ~0.1ms | ~3-10ms (Redis RTT) | 단일 인스턴스면 InProcess |
| Multi-instance coalesce | ❌ | ✅ | 멀티면 Redis 필수 |
| Failure mode | Process crash → all inflight 손실 | TTL 만료 후 재배정 | Redis 가 더 robust |
| SPOF | None | Redis (mitigation 필요) | 운영 복잡성 |
| Lock semantics | atomic computeIfAbsent | SETNX + Lua | 정확성 동일 |
| Result propagation | Promise sharing | Pub/Sub or polling | Redis 는 별도 메커니즘 |
| Operational complexity | Zero | Redis 의존 추가 | 운영 부담 |

## Planned Alternatives

채택 시 비교할 옵션:

### Alternative A — InProcess only (현재)
- 단일 인스턴스 평생, 멀티 인스턴스 안 가는 케이스

### Alternative B — Redis SETNX + Lua + Pub/Sub
- 분산 락 + 결과 broadcast
- 우리가 가장 강력한 후보로 보는 방향

### Alternative C — Redis SETNX + Polling
- 분산 락 + waiter 가 결과 polling
- Pub/Sub 없는 경량 버전

### Alternative D — DB row lock (`SELECT FOR UPDATE`)
- 이미 DB 있다면 추가 의존성 없음
- DB 부하 증가 risk

### Alternative E — Hybrid (InProcess + Redis fallback)
- InProcess 우선, miss 시 Redis 검색
- 복잡성 vs 성능 trade-off

## Planned Validation

Phase 3 측정으로 검증할 hypothesis:

- 🎯 H1: InProcess 단일 인스턴스 P99 vs Redis 단일 인스턴스 P99 차이는 5-10ms
- 🎯 H2: 2 인스턴스 환경에서 InProcess (race 발생) vs Redis (cross-coalesce) 외부 호출 수 차이
- 🎯 H3: Redis 장애 시 fallback 정책의 적절성

## To Be Filled

- [ ] Phase 3 시작 시 Status: Proposed 로 변경
- [ ] Concrete Decision (어떤 Alternative 채택)
- [ ] Implementation notes (Redisson vs raw Lua, lock TTL 값, Pub/Sub vs polling)
- [ ] Measured results (H1~H3 검증)
- [ ] Phase 3 완료 시 Status: Accepted

## References (Planned)

- [Distributed lock by Martin Kleppmann](https://martin.kleppmann.com/2016/02/08/how-to-do-distributed-locking.html)
- Redisson docs: https://redisson.org/
- ADR-001 (Port/Adapter — 이 ADR 의 전제)
