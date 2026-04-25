# Benchmark — k6 Scripts (📅 Planned)

> Phase 2~6 에 추가됨.

## Planned Scenarios

[docs/BENCHMARK.md](../../docs/BENCHMARK.md) 에 정의된 3 시나리오 각각 k6 script 로:

### Scenario 1: Burst Coalescing
한 instant 에 100 동시 요청. Pure single-flight 효과 검증 (외부 호출 1회 가설).

### Scenario 2: Sustained + Cache TTL
60s 지속 부하. Cache TTL 5분 가정. 외부 호출 ~ 1회 (TTL 동안) 가설.

### Scenario 3: Pure Single-Flight (No Cache)
60s 지속 부하 + Cache 비활성. Owner 완료 후 다음 wave 새 owner. 외부 호출 ~ 120회 가설 (60s ÷ 500ms = 120).

## Planned Files

- `mvc-baseline.js` — Phase 2 — coalesce 없음
- `mvc-coalesced-inprocess.js` — Phase 2 — in-process coalesce
- `coalesced-redis-multi.js` — Phase 3 — multi-instance Redis coalesce
- `webflux-baseline.js` — Phase 6 — reactive baseline
- `webflux-coalesced.js` — Phase 6 — reactive coalesce

## Common Conventions

- Warm-up 30s + sustained 60s + cool-down 30s
- 100 VU
- 동일 key (worst case for thundering herd)
- 3회 반복, 중앙값 사용
- 결과는 `benchmark/results/<YYYY-MM-DD>/` 에 저장
