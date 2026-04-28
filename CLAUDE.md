# single-flight-coordinator-lab Project Instructions

## 테스트 작성 규칙 — 실행 가능한 명세서(Executable Specification) 스타일

이 프로젝트의 테스트 클래스는 **계약 문서**로 기능한다. 누군가 파일을 열어 위에서
아래로 읽으면 클래스가 무엇을 하고 무엇을 보장하는지 한 편의 이야기처럼 들어와야
한다.

**모범 사례**: `coordinator-core/src/test/java/com/portfolio/singleflight/coordinator/adapter/InProcessSingleFlightCoordinatorTest.java`

### 구조 5원칙

#### 1. 클래스 헤더 = 계약서

클래스 Javadoc 맨 위에 다음 두 가지를 명시한다.

- **변경자에게 보내는 메시지**: "이 테스트가 모두 초록색이 아니면 X의 약속 중
  하나가 깨진 것이다."
- **이 클래스가 보장하는 N가지 속성**: 번호 매긴 목록으로 한눈에 보이게.

클래스 `@DisplayName`은 클래스가 뭐 하는 놈인지 한 줄 요약.

#### 2. `@Nested` = 보장 속성 단위로 그룹화

기능별/메서드별이 아니라 **외부에 약속한 행동 속성별**로 묶는다. 그룹 번호를
DisplayName 앞에 붙여 클래스 헤더의 N번 속성과 1:1 대응시킨다.

예: `[1] 코알레싱`, `[2] 결과 일관성`, `[3] 자원 정리`, `[4] kill switch`,
`[5] 용량 제한`, `[6] 호출자 격리`, `[7] 입력 계약`, `[8] 관측성`.

각 `@Nested` 클래스의 `@DisplayName`이 그 절의 한 줄 명세 역할을 한다.

#### 3. `@DisplayName` = 한국어 자연어 명세

- 모든 레벨(클래스, `@Nested`, `@Test`)에 한국어 `@DisplayName`을 작성한다.
- 메서드명은 영어(IDE/도구 친화), `@DisplayName`은 한국어(사람 친화).
- "X일 때 Y한다" / "X는 Y한다" 형태의 **완성된 문장**으로 쓴다.

```java
@Test
@DisplayName("동시 20건이면 작업 람다는 단 1번 실행되고 20명 모두 같은 값을 받는다")
void concurrentCallsCoalesceIntoSingleInvocation() { ... }
```

#### 4. 본문은 AAA / Given-When-Then, 노이즈는 헬퍼로 빼낸다

테스트 본문은 시나리오와 단언만 남기고, 반복되는 비동기 동기화·세팅 패턴은
**헬퍼로 추출**한다. 주석은 `WHY`가 비자명할 때만.

```java
// Before
await().atMost(2, TimeUnit.SECONDS)
        .until(() -> coordinator.getInflightState().size() == 1
                && coordinator.getInflightState().get(0).waiterCount() == 20);

// After
awaitSingleInflightWithWaiterCount(20);
```

#### 5. 헬퍼는 클래스 하단 한 군데에 모은다

`awaitNoInflight()`, `awaitSingleInflightWithWaiterCount(int)` 처럼
동시성 동기화 헬퍼는 클래스 맨 아래에 묶어둔다. 이름만 봐도 의도가 그대로
드러나게 짓는다.

### 모든 테스트는 계약이다

- 이 클래스를 수정하는 사람은 **모든 테스트가 초록색인 상태로 PR을 올려야 한다.**
- 의도된 계약 변경이라면 해당 테스트도 함께 갱신하고 PR 설명에 사유를 남긴다.
- 빈틈을 발견하면 테스트를 우회하지 말고 **테스트를 추가해 명세에 박아넣는다.**
- 우회로 통과시키지 말 것 — 실제 결함이면 구현체를 고친다.

### 새 테스트를 추가할 때 체크리스트

- [ ] 어느 보장 속성에 해당하는가? 기존 `@Nested`에 들어가는가, 새 그룹이 필요한가?
- [ ] `@DisplayName`이 한국어 자연어 한 줄 명세로 읽히는가?
- [ ] 본문이 시나리오 의도만 보여주는가? 반복 패턴은 헬퍼로 빠졌는가?
- [ ] 통과시키기 위해 구현체의 의도를 우회하지 않았는가?
- [ ] 클래스 헤더의 "보장 N가지" 목록이 새 속성을 추가했다면 갱신됐는가?

## Git 커밋 / 머지 규칙 — Conventional Commits

언제든 **기능 단위로 롤백·revert**가 가능하도록, 커밋은 작고 의미 있는 단위로
나눈다. 메시지는 [Conventional Commits](https://www.conventionalcommits.org/) 형식을 따른다.

### 메시지 포맷

```
<type>(<scope>): <짧은 한 줄 요약 — 명령형, 마침표 없음>

<선택: 본문 — 왜 이 변경이 필요한지, 어떤 문제를 푸는지>

<선택: footer — Refs/Closes #123 등>
```

- **요약 한 줄**: 50자 이내가 이상적, 명령형 현재시제로 ("add ...", "fix ...", "register ..."). 한국어도 OK.
- **본문**: WHAT보다 **WHY**. diff를 보면 무엇이 바뀐지는 알 수 있으니, 메시지는 동기·맥락·트레이드오프를 남긴다.

### `<type>` 종류

| type | 언제 쓰는가 |
|---|---|
| `feat` | 사용자에게 보이는 새 기능 추가 |
| `fix` | 버그 수정 (테스트로 입증되는 정정) |
| `test` | 테스트 추가/리팩터/재구조 — 프로덕션 동작 변경 없음 |
| `refactor` | 동작 보존하는 내부 구조 개선 |
| `docs` | README/CLAUDE.md/Javadoc 등 문서만 |
| `chore` | 빌드 도구, 의존성, wrapper, gitignore 등 잡일 |
| `build` | 빌드 시스템 변경 (Gradle 설정, CI 파이프라인 등) |
| `perf` | 동작 보존하는 성능 개선 |
| `style` | 포매팅, 세미콜론 등 — 동작·구조 변경 없음 |
| `revert` | 이전 커밋 되돌리기 (`git revert`) |

### `<scope>` 권장 — 모듈 디렉터리명

- `coordinator-core`, `naver-service`, `benchmark`, `infra` 등 멀티모듈 이름
- 빌드 전반은 `build`, 문서 전반은 생략 가능

### 좋은 예 / 나쁜 예

✅ 좋은 예
```
fix(coordinator-core): register cleanup hook after record insertion

If the supplier returned an already-completed future (cache hit,
sync throw → failedFuture), whenComplete fired synchronously inside
the compute lambda — before the record was inserted into the map —
so the evict was a no-op and the entry leaked. Move registration
outside compute so the record is visible by the time the hook runs.
```

❌ 나쁜 예
```
update code        ← 무엇이 바뀐지 알 수 없음
fix bug            ← 어떤 버그인지 불명
WIP                ← merge 전엔 squash 또는 rewrite
asdf               ← 절대 금지
```

### 커밋 분할 원칙 — "기능 단위"

한 커밋 = 한 가지 의도. **언제든 단독으로 revert 가능해야 한다.**

- 두 가지 버그를 한 번에 고치지 말 것 → 두 커밋으로 분리
- 테스트 리팩터와 새 기능을 섞지 말 것 → `test:` 와 `feat:` 분리
- 한 PR 안에 여러 커밋이 있는 건 OK, 다만 각 커밋이 **개별로 빌드/테스트가 통과**해야 함

### 머지 전략

- 작업 브랜치는 `feat/...`, `fix/...`, `chore/...` 등 prefix를 따른다.
- main으로 들어갈 땐 **squash merge**가 기본(작은 변경) — PR 제목이 squash 커밋 메시지가 되므로 PR 제목도 Conventional Commits를 따른다.
- 내부 단계가 의미 있는 큰 변경(스토리가 있는 리팩터 등)은 **rebase merge**로 커밋 히스토리를 보존한다.

### 변경 분류가 애매할 때

- 코드는 안 바뀌고 동작도 안 바뀌면 → `style` / `docs` / `chore`
- 사용자가 보는 행동이 바뀌면 → `feat` 또는 `fix`
- 내부만 바뀌고 외부 동작 같으면 → `refactor` / `perf`
- 테스트만 바뀌면 → `test`

확신이 안 서면 PR에서 합의를 본다 — 일관성이 정확한 분류보다 중요하다.
