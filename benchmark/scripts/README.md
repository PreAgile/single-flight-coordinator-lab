# Benchmark — Automation Scripts (📅 Planned)

> Phase 별 측정 자동화. Phase 2~6 에 추가됨.

## Planned Scripts

```
scripts/
├── run-mvc.sh               (Phase 2) — MVC baseline + coalesced 자동 실행
├── run-redis-multi.sh       (Phase 3) — 2 인스턴스 setup + 측정
├── run-webflux.sh           (Phase 6) — WebFlux baseline + coalesced
├── snapshot-prom.sh         — Prometheus 스냅샷 (날짜별)
└── compare-results.sh       — 두 결과 file 의 표 비교
```

## Planned Behavior

각 `run-*.sh` 의 실행 흐름:

```bash
1. 인프라 health check (docker-compose ps)
2. 기준선 측정 (warm-up 30s)
3. k6 baseline 실행 (sustained 60s)
4. 결과 저장 → benchmark/results/<날짜>/
5. k6 coalesced 실행 (sustained 60s)
6. 결과 저장
7. compare-results.sh 로 표 생성
8. Grafana snapshot (/api/snapshots) 로 그래프 export
```
