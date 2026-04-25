# ADR-003 — Decorator Stack Order

| Field | Value |
|---|---|
| Status | **📅 Planned** (Phase 4 시작 시 작성) |
| Date | TBD |
| Phase | 4 |
| Deciders | @PreAgile |
| Related | [ADR-001](ADR-001-port-adapter-pattern.md), [ADR-004](ADR-004-coordinator-vs-caffeine.md) |

---

## Status: Planned

이 ADR 은 **placeholder** 입니다. Phase 4 (Cross-language) 시작 시 실제 결정 + 검증으로 채워집니다.

현재까지의 hypothesis 만 outline.

---

## Planned Context

5 layer chain 의 decorator 순서가 의미 가짐:

```
Base (InProcess) → Deadline → Capacity → Heartbeat → Telemetry (outermost)
```

순서가 바뀌면:
- Telemetry 가 inner 면 deadline exception 분류 못 함 (status=hang 으로 보임)
- Capacity 가 Base 와 분리되면 atomic 검사 깨짐 ([ADR-001 의 capacity nuance](ADR-001-port-adapter-pattern.md) 참고)

## Planned Hypothesis

🎯 **H1**: Telemetry 가 outermost 일 때 deadline / congestion 분류 정확.
🎯 **H2**: Capacity 가 Base 와 atomic 결합 안 되면 race condition 발생.
🎯 **H3**: Heartbeat 의 timer 시작은 base record 와 같은 thread context 에서.

## Planned Validation

Phase 4 의 cross-paradigm 검증으로:
- 같은 stack 순서가 TS / Java 둘 다 옳은가
- 순서 바뀌면 어떤 시나리오에서 깨지는지 ConcreteTest 로 시연

## To Be Filled

- [ ] Phase 4 시작 시 Status: Proposed
- [ ] Concrete decision: 정확한 stack 순서 + 각 위치 이유
- [ ] Anti-pattern: 순서 바꾸면 어떤 bug
- [ ] TS reference 와 Java 가 같은 순서 채택하는지 비교
- [ ] Phase 4 완료 시 Status: Accepted
