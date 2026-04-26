# Contributing to single-flight-coordinator-lab

이 문서는 외부 기여자(혹은 미래의 본인)를 위한 가이드입니다. 프로젝트의 컨벤션
은 `CLAUDE.md`에 단일 출처로 모여 있고, 이 파일은 그 컨벤션을 **PR 흐름의
관점에서** 정리한 빠른 참조서입니다.

> **단일 출처 규칙**: 컨벤션 자체가 바뀌어야 한다면 `CLAUDE.md`를 먼저 갱신하고,
> 그 후 이 파일이 가리키는 섹션을 동기화한다. 두 곳에 같은 규칙을 쓰지 않는다.

## 빠른 시작

```bash
git clone https://github.com/PreAgile/single-flight-coordinator-lab.git
cd single-flight-coordinator-lab
./gradlew check        # 전체 검증 (포맷 + 컴파일 + 정적 분석 + 테스트)
```

JDK 21 권장 (프로젝트 toolchain). Gradle wrapper가 동봉돼 있으므로 별도 Gradle
설치 불필요.

## 개발 워크플로

### 일상 명령어

| 목적 | 명령 |
|---|---|
| 전체 검증 (CI 게이트와 동일) | `./gradlew check` |
| 포맷 자동 적용 | `./gradlew spotlessApply` |
| 포맷 위반 검사만 | `./gradlew spotlessCheck` |
| 특정 모듈 테스트 | `./gradlew :coordinator-core:test` |
| 특정 테스트 클래스 | `./gradlew :coordinator-core:test --tests "...InProcessSingleFlightCoordinatorTest"` |
| Gradle daemon 정리 | `./gradlew --stop` |

`./gradlew check`가 초록색이어야 PR 머지 가능. 로컬에서 확인 후 push.

### 브랜치 전략

브랜치 이름은 Conventional Commits의 type prefix를 따른다:

- `feat/<무엇>` — 새 기능
- `fix/<무엇>` — 버그 수정
- `chore/<무엇>` — 빌드/도구/잡일
- `docs/<무엇>` — 문서
- `refactor/<무엇>` — 동작 보존 리팩터
- `test/<무엇>` — 테스트만

main에서 분기, main으로 PR.

## 코드 스타일

### Java

- Java 21 사용. 새 코드는 record / sealed / pattern matching / switch
  expression / text block을 우선 활용한다.
- 들여쓰기 4 스페이스 (탭 금지)
- import 그룹 순서: `java.*` → `javax.*` → 그 외 → `com.portfolio.*`
  (Spotless가 자동 정렬)
- public API에는 `Objects.requireNonNull(arg, "arg")` 같은 fail-fast null 검사
- 절대 null이 될 수 없는 곳에 ceremonial null check 추가하지 않음
- 주석은 **WHY가 비자명할 때만**. 자명한 한 줄 주석, 의미 없는 docstring,
  `// removed X` 같은 흔적 주석은 거부 대상

자세한 가이드: `CLAUDE.md` 의 "Doing tasks" / "Tone and style" 섹션.

### Spotless

`./gradlew spotlessApply`로 자동 정리되는 항목:
- import 그룹 정렬 + 미사용 import 제거
- 줄 끝 trailing whitespace 제거
- 파일 끝 newline

CI는 `spotlessCheck`로 게이트한다. 위반이 있으면 `./gradlew spotlessApply`
실행 후 다시 push.

### ErrorProne

컴파일 시 정적 분석이 자동 실행된다 (main 소스 한정, test 소스 제외). 흔한
함정 — equals/hashCode 비대칭, format string 오류, Future 결과 무시 등 —
이 컴파일 단계에서 잡힌다. 경고가 뜨면 다음 중 하나:

1. **고친다** (대부분의 경우 정답)
2. ErrorProne이 추천한 패턴(`var unused = ...` 등)으로 의도를 명시한다
3. 정말 false-positive면 `@SuppressWarnings("Errorprone:CheckName")`로 좁게 억제

전역 비활성화는 PR에서 합의를 본다.

## 테스트 작성 — 실행 가능한 명세서 스타일

이 프로젝트의 테스트 클래스는 **계약 문서**로 기능한다. 누군가 파일을 열어
위에서 아래로 읽으면 클래스가 무엇을 보장하는지 한 편의 이야기처럼 들어와야
한다.

**모범 사례**: `coordinator-core/src/test/java/com/portfolio/singleflight/coordinator/adapter/InProcessSingleFlightCoordinatorTest.java`

### 5원칙 (요약 — 자세한 내용은 `CLAUDE.md`)

1. **클래스 헤더 = 계약서** — 클래스 Javadoc에 변경자 메시지 + 보장 N가지
   속성 명시
2. **`@Nested` = 보장 속성 단위 그룹화** — `[1] 코알레싱`, `[2] 결과 일관성`
   처럼 번호 부여, 헤더 N번과 1:1 대응
3. **한국어 `@DisplayName`** — 모든 레벨, "X일 때 Y한다" 완성 문장
4. **AAA 본문 + 헬퍼 추출** — 반복되는 비동기 동기화는 헬퍼로
5. **헬퍼는 클래스 하단** — `awaitNoInflight()` 처럼 이름이 의도를 그대로

### 새 테스트 추가 체크리스트

- [ ] 어느 보장 속성에 해당하는가? 기존 `@Nested`에 들어가는가, 새 그룹이 필요한가?
- [ ] `@DisplayName`이 한국어 자연어 한 줄 명세로 읽히는가?
- [ ] 본문이 시나리오 의도만 보여주는가? 반복 패턴은 헬퍼로 빠졌는가?
- [ ] 통과시키기 위해 구현체 의도를 우회하지 않았는가?
- [ ] 클래스 헤더의 "보장 N가지" 목록 갱신이 필요한가?

## Commit / PR

### Conventional Commits

메시지 포맷:
```
<type>(<scope>): <짧은 한 줄 요약>

<선택: 본문 — WHY 중심>

<선택: footer — Refs/Closes #123>
```

`<type>`: `feat` / `fix` / `test` / `refactor` / `docs` / `chore` / `build` /
`perf` / `style` / `revert`. 스코프는 `coordinator-core`, `naver-service`,
`benchmark`, `infra`, `build` 등 모듈명.

### 한 커밋 = 한 의도

언제든 단독 revert 가능해야 한다. 두 버그 동시에 고치지 말고, 테스트 리팩터와
새 기능을 섞지 말 것. 한 PR 안에 여러 커밋이 있는 건 OK이지만 각 커밋이
**개별로 빌드/테스트가 통과**해야 한다.

자세한 분류 가이드: `CLAUDE.md` "Git 커밋 / 머지 규칙" 섹션.

### PR 가이드

PR 제목도 Conventional Commits 형식. PR 본문은 다음 구조 권장:

```markdown
## Summary
- 무엇을 왜 바꾸는지 1-3줄

## Test plan
- [ ] 어떻게 검증했는지 체크리스트
- [ ] `./gradlew check` 통과 확인
```

### 머지 전략

- **squash merge** — 작은 변경 (대부분의 PR)
- **rebase merge** — 의미 있는 단계 히스토리를 보존하고 싶은 큰 리팩터
- **merge commit** — 사용 안 함

PR 제목이 squash 후 메시지가 되니 제목도 commit 형식을 따른다.

## 코드 리뷰

### 자동 리뷰

CodeRabbit이 한국어로 자동 리뷰를 등록한다 (`.coderabbit.yaml` 참조). 발견된
이슈는 advisory가 아니라 **변경 요청**으로 등록되므로 답변/수정 없이는 머지
불가.

CodeRabbit이 제안한 변경이 부적절하다고 판단되면, 무시하지 말고 **거절 사유를
명시적으로 답변**한다 (예: "이 프로젝트는 그룹별 단일 관심사 원칙을 따르므로
이 그룹에 cleanup 단언을 추가하지 않습니다 — 클래스 Javadoc 인용").

### 사람 리뷰

작은 변경은 self-merge 가능 (단독 기여 단계). 외부 기여자나 큰 변경은 최소
1명의 review approve 필요.

리뷰어는 다음 두 질문에 답하면서 마무리한다:
1. **이 변경이 `CLAUDE.md`의 컨벤션과 일치하는가?**
2. **이 PR이 머지되면 어느 contract 테스트가 새로 깨질 가능성이 있는가?**

## 자주 묻는 질문

### "구현은 그대로인데 테스트 통과시키려고 단언을 약화해도 되나요?"

**아니요.** 그건 우회입니다. 테스트가 깨지면 둘 중 하나:
- 정말 결함이면 → 구현을 고친다
- 의도된 계약 변경이면 → 테스트를 갱신하고 PR에 사유를 명시한다

### "한 PR에 여러 fix를 묶어도 되나요?"

원칙적으로 한 PR = 한 의도. 단, 같은 PR 안에 여러 커밋으로 분리하면 OK입니다.
예: 하나의 fix가 5곳에 영향을 미친다면 그 5개를 한 커밋으로 묶고 PR로 올린다.
서로 무관한 두 fix는 두 PR로.

### "Spotless가 자꾸 import를 재정렬하는데 왜죠?"

`./gradlew spotlessApply`를 commit 전에 한 번 돌리세요. IDE 설정에 같은
import order를 넣으면 (`java`, `javax`, ``, `com.portfolio` 그룹) 매번 재정렬
부담이 사라집니다.

### "ErrorProne이 너무 시끄러워요"

특정 케이스에 정말 false-positive면 좁은 `@SuppressWarnings`로 처리. 전역
비활성화는 PR로 합의.

## 도움 요청

- 이슈 등록: GitHub Issues
- 보안 이슈: 이슈 트래커에 직접 적지 말고 메인테이너에게 비공개 연락

— Maintainers
