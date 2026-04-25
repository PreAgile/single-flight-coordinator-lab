# INCIDENT — Distributed Login Lockout via Concurrent Session Refresh

> **Real production incident.** 회사명 / 외부 SaaS platform 명 / 정확한 시각·수치는 NDA 보호 위해 익명화. 패턴 / timeline 구조 / 측정 방법론은 실제 그대로.
>
> 이 문서는 [STORY.md](STORY.md) 의 Act 2 (The Incident) 의 상세 부록. STORY 가 narrative 라면 이 문서는 **forensic timeline + customer 영향**.

---

## TL;DR

```
WHO       Customer A (영등포 외식 프랜차이즈 가맹점 owner)
WHAT      Batch reply 기능 사용 중 외부 SaaS 계정 1시간 lock
WHY       Per-user 직렬화 부재 → 80초 안에 10개 IP 로 동일 user 로그인
          → 외부 시스템이 distributed brute-force 로 감지
SCOPE     24h 동안 5명 customer 동일 패턴
IMPACT    평균 lock 47분 / CS 통화 14분 / 신뢰 손상
```

---

## Customer Persona — Customer A

> 익명화된 합성 페르소나. 실 customer 의 demographic 만 일반화.

**역할**: 외식 프랜차이즈 가맹점 owner (영등포)
**규모**: 일평균 신규 리뷰 50건
**사용 패턴**: 매일 일과 끝 (22:00~24:00) 에 답글 일괄 작성
**우리 SaaS 의존도**: batch reply 가 본인의 핵심 도구 — 답글 30분 → 5분 단축
**기술 친숙도**: 일반 사용자 (관리자 메뉴는 대시보드까지만)

이 페르소나의 의미:
- batch 기능에 가장 의존적인 사용자 segment
- batch 가 안 되면 즉시 매출 영향 (다음날 아침 review 답변 누락 → 잠재 customer 이탈)
- CS 문의 시 "내가 해킹당했나?" 부터 의심 (휴대폰 알림 5통 받기 때문)

---

## Detailed Timeline (KST)

> 분 단위 정밀도. 실 incident 시각은 익명화 (시간대만 보존).

### 22:42:13 — Trigger

Customer A 가 cmong-admin 의 **Batch Reply** UI 에서 "전체 답변" 버튼 클릭.
- 대상 리뷰: **20개**
- 발사 패턴: UI 가 `setTimeout(req, getRandomNumberInRange(1000, 3000))` 으로 1~3초 간격 발사
- 총 발사 소요: 67초

### 22:42:18 — First ensureSession

scraper-js 가 첫 번째 reply 요청 처리:
```
[NaverService] ensureSession user=user-X  (1번째)
  cache.get(user-X) → MISS (TTL 만료)
  externalLogin(user-X)
    proxy.allocate(user-X) → IP 1.2.3.4 (proxy A)
    naverLogin via Playwright
    cookies received
  cache.set(user-X, cookies)
  return cookies
```

소요: 약 3 초 (Playwright + 외부 로그인). 이 동안 다음 요청들이 도착.

### 22:42:21 — Second concurrent ensureSession

```
[NaverService] ensureSession user=user-X  (2번째 — 1번째 진행 중!)
  cache.get(user-X) → MISS (1번째가 아직 cache.set 안 함)
  externalLogin(user-X)
    proxy.allocate(user-X) → IP 5.6.7.8 (proxy B)  ★ 다른 IP!
```

→ 1번째와 동시 진행. proxy 풀의 user lock 이 깜박이는 마이크로 윈도우 동안 새 IP 할당.

### 22:42:24 ~ 22:43:35 — Cascade

```
3번째 ensureSession → IP 9.10.11.12   (proxy C)
4번째               → IP 13.14.15.16  (proxy D)
5번째               → IP 17.18.19.20  (proxy E)
6번째               → IP 21.22.23.24  (proxy F)
7번째               → IP 25.26.27.28  (proxy G)
8번째               → IP 29.30.31.32  (proxy H)
9번째               → IP 33.34.35.36  (proxy I)
10번째              → IP 37.38.39.40  (proxy J)
... (이후는 일부 cache hit, 다른 IP 도 일부 재사용)
```

**80초 윈도우 안에 unique IP 10개 → 같은 user-X 로 외부 시스템 로그인 시도.**

### 22:43:43 — Detection

외부 시스템의 보안 로직 발동:
- "user-X 가 80초 안에 10개 IP 로 로그인 시도"
- 알고리즘: distributed login attempt / credential stuffing 판단
- → **user-X 계정에 보호조치 발동 (1시간 lock)**

### 22:43:44 — Our system receives protect-setting page

```
[NaverService] readyContextPageWithRetry
  goto smartplace.naver.com
  → 응답: "보호조치 로그인" 페이지 (✗ 이전엔 본 적 없는 페이지)
  → throw NaverProtectSettingException
  → 11번째~20번째 reply 요청들도 모두 동일 페이지 받고 실패
```

우리 시스템이 처음 본 응답 → exception classification 보강 필요 (별도 issue).

### 22:43:50 ~ 22:43:55 — Mail bombs

외부 시스템이 user-X 의 등록된 휴대폰 + 이메일에:
- "새로운 환경에서 로그인이 감지되었습니다" 알림 **5통** (5개 IP 가 외부에서 보안 임계값 초과한 것)

Customer A 휴대폰에 22:43:50 부터 1초 간격으로 알림 5번.

### 22:48:00 — Customer realizes

Customer A 가 batch 결과 확인 → 모든 답글 실패 표시 → "내가 해킹당했나?" 의심.
- 휴대폰 알림 5통 → 외부 SaaS 직접 로그인 시도 (lock 됐는지 확인)
- → 로그인 실패. "보호조치" 페이지.
- → CS 문의 시작.

### 22:55:00 — CS call

Customer Support 팀이 인지:
- "Customer A 의 외부 SaaS 계정이 lock 됐습니다."
- 1차 가이드: 외부 SaaS 직접 비밀번호 변경 → 보호조치 해제 안내
- 통화 시간: **17 분**

Customer A: 답글 작성 못 한 채 일과 종료. 다음 날 아침 답글 수동 처리.

### 23:43 — Auto-unlock

외부 시스템의 1시간 자동 lock 만료. Customer A 가 다시 우리 SaaS 사용 가능. 단, 본인은 이를 즉시 인지 못 함 (이미 일과 종료).

---

## Scope of Impact (24h Monitoring)

이 incident 가 단발이 아니었다. RCA 후 24h 모니터링 결과:

```
2026-04-22 22:42 ~ 2026-04-23 22:42 (24h)

Customer 영향: 5명 (B, C, D, E, F + Customer A)
공통 패턴:
  - 모두 batch reply 또는 batch 광고 보고서 등록 사용
  - 평균 동시 요청: 15~25개
  - 평균 다른 IP 할당: 7~12개
  - 평균 lock 시간: 47분 (auto-unlock 또는 직접 비밀번호 변경)
  - CS 통화: 평균 14분/건
```

CS 팀 신호:
> "최근 batch reply 후 외부 SaaS 차단된다는 문의가 평소 대비 3배 증가"

→ Engineering 팀 escalation. RCA 시작.

---

## Root Cause Analysis

### Code Path

문제의 함수 (의사코드 — 실 코드 아님, 패턴만 동일):

```typescript
async ensureSession(userId: string): Promise<Session> {
  const cached = await cache.get(userId);
  if (cached && validate(cached)) return cached;

  // ★ 동시 호출 N 개가 모두 여기 도달
  const newSession = await externalLogin(userId);
  await cache.set(userId, newSession);
  return newSession;
}
```

### Observable Behavior

1. **Trigger condition**: 동일 userId 에 대한 동시 호출 N (≥ 2)
2. **First step**: 모두 `cache.get(userId)` 호출 → **모두 MISS** (cache 에 아직 결과 박힘)
3. **Second step**: 모두 `externalLogin(userId)` 호출 → **N 개 외부 로그인**
4. **Side effect**: 각자 다른 proxy IP 할당 (proxy 풀의 user lock 이 마이크로 윈도우 동안 깜박)
5. **External perception**: 외부 시스템이 N 개 IP 로부터 동일 user 로그인 시도 감지

### Why Not Caught Earlier

- **단위 테스트 부재**: 동시 호출 시나리오 테스트 안 됨
- **부하 테스트 부재**: batch UI 의 동시 패턴이 실 부하로 측정 안 됨
- **Telemetry 부재**: proxy IP 할당 로그를 alerting 으로 연결 안 함
- **외부 시스템 보안 정책 미문서화**: 어떤 패턴이 brute-force 로 감지되는지 우리 팀 모름
- **Code review 의 한계**: ensureSession 가 작성될 때 batch UI 가 없었음. 추후 batch 추가 시 ensureSession 의 동시 호출 가정 재검토 안 됨

→ 이 5가지 모두 [STORY.md](STORY.md) Act 7 (Lesson) 의 "다시 하면 다르게 할 것" 에 정리됨.

---

## Solution Trade-offs

4가지 대안 검토 → Single-Flight 채택. 상세는 [STORY.md](STORY.md) Act 4 + [adr/ADR-001-port-adapter-pattern.md](adr/ADR-001-port-adapter-pattern.md).

---

## Verification Plan

이 incident 의 Before/After 를 portfolio repo 에서 재현 가능하게 측정:

| Phase | 측정 대상 | 방법 |
|---|---|---|
| 2 | MVC + thread pool 통점 | k6 100 동시 → Tomcat busy% / 외부 호출 수 |
| 3 | Multi-instance coalesce | 2 인스턴스 + Redis SETNX → cross-instance 외부 호출 1번 검증 |
| 6 | WebFlux + connection 통점 | k6 100 동시 → WebClient connection / Mono coalesce 검증 |

→ [BENCHMARK.md](BENCHMARK.md) 에 결과 정리.

---

## NDA / Anonymization Notes

이 문서가 노출하지 않는 것:
- ❌ 실 회사명, 서비스명
- ❌ 실 외부 SaaS platform 명
- ❌ 실 customer 식별 정보
- ❌ 실 시각 (날짜는 시간대만)
- ❌ 실 매출 / customer 수치 / lock 시간 정확값
- ❌ 내부 시스템 metric 이름 / dashboard URL

이 문서가 노출하는 것:
- ✅ 패턴 (동시 호출 + per-user 직렬화 부재)
- ✅ Timeline 구조 (분 단위 sequence)
- ✅ 측정 방법론
- ✅ Trade-off 분석
- ✅ 일반화 가능한 lessons learned

면접에서 디테일 질문 시 표준 답변:
> "비공개 NDA 라 회사명 / 외부 platform 명 / 정확 수치는 답 못 드립니다. 단 패턴 / 해법 / 일반화 통찰은 [GitHub repo] 에 정리돼있습니다."

---

## Related

- [STORY.md](STORY.md) — narrative arc 7-act
- [DESIGN.md](DESIGN.md) — Hexagonal + Decorator 설계
- [BENCHMARK.md](BENCHMARK.md) — 재현 가능한 측정
- [adr/ADR-001-port-adapter-pattern.md](adr/ADR-001-port-adapter-pattern.md) — Port/Adapter 채택 근거
- [GitHub Issues](https://github.com/PreAgile/single-flight-coordinator-lab/issues) — Phase 1~6 진행
