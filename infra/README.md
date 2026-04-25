# Infra — 📅 Planned

> Phase 2 (mvc-demo + 측정 인프라) 시작 시 채워짐.

## Planned Layout

```
infra/
├── docker-compose.yml          (Phase 2)
├── prometheus/
│   └── prometheus.yml
└── grafana/
    ├── provisioning/
    │   ├── dashboards/default.yml
    │   └── datasources/prometheus.yml
    └── dashboards/
        ├── mvc-single-flight.json
        └── webflux-single-flight.json (Phase 6)
```

## Planned Services

- **app** (mvc-demo or webflux-demo) — Spring Boot 3.x
- **redis** (Phase 3) — 7-alpine
- **prometheus** — latest, 15s scrape interval
- **grafana** — latest, admin/admin, dashboards auto-provisioned

## Planned Quick Start

```bash
# Phase 2 완료 후 동작 예정:
cd infra && docker-compose up -d

# Grafana
open http://localhost:3000

# Prometheus
open http://localhost:9090
```

## Status

- [ ] Phase 2 시작 시 docker-compose.yml 작성
- [ ] Prometheus scrape config
- [ ] Grafana provisioning + dashboard
- [ ] Phase 3 시작 시 redis service 추가
- [ ] Phase 6 시작 시 webflux dashboard 추가

## References

- [BENCHMARK.md](../docs/BENCHMARK.md) — 측정 방법론 + 예상 결과
- [Issue #3 (Phase 2)](https://github.com/PreAgile/single-flight-coordinator-lab/issues/3)
- [Issue #4 (Phase 3)](https://github.com/PreAgile/single-flight-coordinator-lab/issues/4)
