# Benchmark — Results Archive (📅 Planned)

> Phase 별 측정 결과의 immutable archive.

## Layout

```
results/
├── README.md                    (이 파일)
└── <YYYY-MM-DD>/                날짜별 측정 batch
    ├── README.md                — 환경 + 조건 요약
    ├── mvc-baseline.md          — k6 raw output + Grafana 캡처
    ├── mvc-coalesced.md
    ├── redis-multi.md           (Phase 3)
    ├── webflux-baseline.md      (Phase 6)
    ├── webflux-coalesced.md
    └── comparison.md            — 시나리오 간 비교 표
```

## Why Archive

- 🎯 **재현성** — 미래의 본인이 같은 환경에서 재측정 시 비교 가능
- 📊 **회귀 추적** — Phase 마다 측정값 변동 추적 (성능 회귀 발견)
- 🎯 **Portfolio 자료** — 면접에서 cite 가능 ("OK, 정확한 측정 결과는 [results/2026-04-30/mvc-coalesced.md] 에 있습니다")

## Convention

각 측정 batch (`<YYYY-MM-DD>/`) 의 README 에 명시:
- Hardware spec (CPU, RAM, Docker resource limits)
- JVM config (heap, GC)
- Workload (VU, duration, key distribution)
- 결과 표 (Hypothesis vs Measured)
- 분석 (왜 그런 결과인지)
- 다음 액션 (개선점, 추가 측정 필요 영역)
