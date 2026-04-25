# ADR-004 — Coordinator vs Caffeine.AsyncCache

| Field | Value |
|---|---|
| Status | **📅 Planned** (Phase 4 시작 시 작성) |
| Date | TBD |
| Phase | 4 |
| Deciders | @PreAgile |
| Related | [ADR-001](ADR-001-port-adapter-pattern.md) |

---

## Status: Planned

이 ADR 은 **placeholder** 입니다. Phase 4 시작 시 실제 비교 + 측정으로 채워집니다.

현재까지의 hypothesis 만 outline.

---

## Planned Context

[Caffeine.AsyncCache](https://github.com/ben-manes/caffeine) 의 `AsyncLoadingCache` 가 single-flight 와 비슷한 coalescing 을 내장 제공:

```java
AsyncLoadingCache<String, Session> cache = Caffeine.newBuilder()
    .expireAfterWrite(Duration.ofMinutes(5))
    .buildAsync(key -> doEnsureSession(key));

CompletableFuture<Session> session = cache.get("user-X");  // coalesce 내장
```

→ "왜 Caffeine 안 쓰고 직접 구현?" 이 면접 자주 나오는 꼬리질문 ([INTERVIEW.md Q1](../INTERVIEW.md)).

## Planned Comparison Dimensions

| Dimension | Caffeine.AsyncCache | Coordinator (직접) |
|---|---|---|
| Coalescing 정확성 | 검증된 라이브러리 | 직접 구현 (검증 필요) |
| 결과 보관 (TTL) | 내장 | 별도 cache 필요 |
| Use case 가정 | Cache (read-heavy) | 일반 비동기 작업 |
| Deadline / Capacity / Telemetry 통합 | 외부 wrap 필요 | Decorator stack 으로 통합 |
| Multi-backend 확장 (Redis 등) | Caffeine 인터페이스 구현 안 함 | Port/Adapter 자연 |
| Maintenance | 라이브러리 의존 | 자체 책임 |

## Planned Hypothesis

🎯 **H1**: Caffeine.AsyncCache 는 cache use case 에 최적, 일반 외부 자원 할당 (login, RPC) 에는 추가 wrap 필요.
🎯 **H2**: 운영 안전망 (deadline / capacity / telemetry) 통합 제어가 필요한 경우 직접 구현이 명확.
🎯 **H3**: Multi-backend 확장 (Redis) 이 명시적 다음 단계면 Hexagonal Port 가 더 자연스러움.

## When Caffeine Wins (예상)

- 단순 read cache (TTL 만료 + coalesce 내장)
- Java/Spring 한정
- 단일 인스턴스 평생
- 운영 안전망 단순 (timeout + 기본 metric 만 필요)

## When Direct Coordinator Wins (예상)

- 외부 자원 할당 (cache 가 아니라 작업 자체)
- 운영 안전망 통합 (4 decorator stack)
- Multi-backend 확장 예정 (in-process → Redis → MQ)
- Cross-language reference 필요 (TS, Kotlin 비교 가능)

→ **우리 case 는 후자 4 가지 모두 해당** → 직접 구현.

## Planned Validation

Phase 4 에서 작은 demo 로 비교:
- 같은 시나리오 (100 동시 요청, 500ms external) 를 Caffeine vs Coordinator 로 풀고
- 코드 라인 수 비교
- Decorator 통합 시 Caffeine wrap 의 복잡도 측정

## To Be Filled

- [ ] Phase 4 시작 시 Status: Proposed
- [ ] Concrete code sample 비교
- [ ] 측정값 (코드 라인 / 통합 비용 / 성능)
- [ ] "어떤 case 어떤 도구" 가이드
- [ ] Phase 4 완료 시 Status: Accepted
