# Benchmark — 📅 Planned

> Phase 별 부하 측정 + 결과 archive. Phase 2~6 진행 시 채워짐.

## Planned Layout

```
benchmark/
├── README.md                    (이 파일)
├── k6/                          k6 부하 스크립트
│   ├── README.md
│   ├── mvc-baseline.js          (Phase 2)
│   ├── mvc-coalesced-inprocess.js
│   ├── coalesced-redis-multi.js (Phase 3)
│   ├── webflux-baseline.js      (Phase 6)
│   └── webflux-coalesced.js
├── scripts/                     자동화 shell scripts
│   ├── README.md
│   ├── run-mvc.sh               (Phase 2)
│   ├── run-redis-multi.sh       (Phase 3)
│   └── run-webflux.sh           (Phase 6)
└── results/                     측정 결과 archive (날짜별)
    ├── README.md
    └── <YYYY-MM-DD>/
        ├── mvc-baseline.md
        ├── mvc-coalesced.md
        └── ...
```

## Planned Workflow

각 Phase 측정 시:

1. `cd infra && docker-compose up -d` — 인프라 기동
2. `bash benchmark/scripts/run-<phase>.sh` — k6 부하 + Prometheus 스냅샷
3. 결과를 `benchmark/results/<YYYY-MM-DD>/` 에 markdown + raw csv 로 저장
4. `docs/BENCHMARK.md` 의 Expected Results 표를 Measured 값으로 갱신

## Status

- [ ] Phase 2 시작 시 mvc 시리즈 작성
- [ ] Phase 3 시작 시 redis-multi 작성
- [ ] Phase 6 시작 시 webflux 시리즈 작성

## References

- [docs/BENCHMARK.md](../docs/BENCHMARK.md) — 측정 방법론 + business impact framework
- [docs/STORY.md Act 6](../docs/STORY.md) — Result narrative (운영 추정값 + 측정 예정 명시)
