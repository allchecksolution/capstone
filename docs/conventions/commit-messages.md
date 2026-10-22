# 커밋 메시지 컨벤션

로컬 작업 커밋과 Squash Merge로 만들어지는 최종 커밋 모두 같은 커밋 규칙을 따릅니다.

## 형식

```text
<type>(<scope>): <description>
```

scope는 선택 사항입니다. scope를 생략하면 괄호도 생략합니다.

```text
feat(dispatch): 긴급 출동 요청 API 구현
fix(auth): JWT 검증 오류 수정
docs: README 작성
chore: 프로젝트 초기 설정
```

## 항목별 규칙

| 항목 | 규칙 |
| --- | --- |
| `type` | 아래 여덟 가지 중 하나. 소문자 사용 |
| `scope` | 선택 사항. 변경한 도메인·모듈 또는 대문자 APP 식별자 |
| `description` | 필수. 콜론 뒤에 공백을 두고 변경 내용을 작성. 한국어 허용 |

| type | 용도 |
| --- | --- |
| `feat` | 기능 추가 |
| `fix` | 오류 수정 |
| `refactor` | 기능 변경 없이 구조 개선 |
| `docs` | 문서 작성·수정 |
| `test` | 테스트 추가·수정 |
| `chore` | 설정·유지보수 등 기타 작업 |
| `build` | 빌드 시스템·의존성 변경 |
| `perf` | 성능 개선 |

`feature`, `hotfix`, `style`, `ci`, `revert`는 type으로 허용하지 않습니다. `build`, `perf`는 커밋에서만 허용하고 브랜치명과 PR 제목에서는 사용하지 않습니다.

scope는 PR의 APP 목록으로 제한하지 않습니다. `auth`, `dispatch` 같은 도메인 이름을 자유롭게 사용할 수 있습니다. APP을 scope로 쓸 때에는 `BE`, `ADMIN`, `DRIVER`, `AI`, `INFRA`, `MULTI`, `COMMON`을 참고합니다. 여러 파트의 변경은 `MULTI`, 공통 설정·문서·자원의 변경은 `COMMON`으로 나타낼 수 있습니다.

Jira 키는 일반 작업 커밋에는 필수가 아닙니다. 커밋 제목 길이 제한은 해제되어 있지만, 핵심 변경을 간결하게 작성합니다. 그 외 Commitlint 기본 규칙에 따라 제목 끝의 마침표(`.`)는 사용하지 않고, 본문·footer의 한 줄 길이는 기본적으로 100자 이내로 작성합니다.

## PR 제목을 사용하는 Squash 커밋

아래 [PR 제목 형식](pull-request-titles.md)도 커밋 검사에 통과합니다.

```text
feat(BE): JWT 기반 로그인 API 구현 [ACK-12]
fix(DRIVER): 위치 전송 오류 수정 [ACK-35]
chore(INFRA): GitHub Actions 설정 [ACK-42]
feat(MULTI): 로그인 API와 관리자 화면 연동 [ACK-51]
docs(COMMON): 팀 공통 협업 규칙 작성 [ACK-53]
```

단, Commitlint는 일반 커밋에 APP이나 Jira 키를 강제하지 않습니다. **PR 제목의 필수 APP·Jira 키와 브랜치 키 일치 여부는 별도의 PR 검사가 담당합니다.**

## 실패 사례와 병합·되돌리기

| 잘못된 예시 | 원인 |
| --- | --- |
| `로그인 기능 구현` | type과 구분 형식 누락 |
| `feature(auth): 로그인 구현` | 허용되지 않는 type |
| `Feat(auth): 로그인 구현` | type에 대문자 사용 |
| `feat(auth):로그인 구현` | 콜론 뒤 공백 누락 |
| `feat:` | description 누락 |

`Merge ...`, `Revert ...`, `v1.0.0`, `fixup! ...` 같은 메시지도 자동으로 검사에서 제외하지 않습니다. 예를 들어 `main` 변경을 작업 브랜치에 병합할 때는 다음처럼 메시지를 지정합니다.

```sh
git merge -m "chore: main 변경 반영" origin/main
```

되돌리기 작업의 커밋도 `fix(auth): 로그인 변경 되돌리기`처럼 허용된 type과 형식으로 작성합니다.

## 검사 및 강제 방식

1. **로컬 커밋**: 저장소 루트에서 최초 `npm ci`를 실행하면 Husky가 활성화됩니다. `.husky/commit-msg`가 Commitlint로 커밋 메시지 파일을 검사하고, 실패하면 커밋을 중단합니다.
2. **CI의 역할**: Squash Merge를 사용하므로 PR 내부의 개별 커밋 메시지는 CI에서 검사하지 않습니다. CI는 PR 소스 브랜치명, PR 제목, Jira 키 일치 여부를 검사하고, 검증된 PR 제목을 최종 커밋 제목으로 사용합니다. 개별 커밋 메시지 검증은 로컬 Hook이 담당합니다.
3. **자동 테스트 유지**: `Git conventions - Validator tests`가 `npm run test:git`을 실행해 검사 로직과 로컬 Hook의 동작을 검증합니다. Commitlint 테스트에는 고정된 정상·실패 예시를 사용하며, 실제 PR의 커밋 이력을 읽거나 검사하지 않습니다.
4. **병합 제한**: 관리자가 브랜치명·PR 제목·Jira 키 일치·Validator tests 네 검사를 `main` Ruleset의 필수 Status Check로 등록합니다. 기존 `Git conventions - Commit messages`는 제거하고 `Git conventions - Validator tests`를 등록해야 합니다. 로컬 Hook은 우회 가능하며, CI에서 개별 커밋 메시지를 재검사하지 않습니다.

로컬 Commitlint 설정인 [`commitlint.config.cjs`](../../commitlint.config.cjs)는 유지합니다. 허용 type은 공통 스크립트에서 가져오고, `scope-case`·`subject-case`를 해제하여 대문자 APP, 한국어, JWT/API 같은 약어를 허용합니다. CI의 자동 테스트도 이 설정을 사용해 로컬 검증 정책이 정상 동작하는지 확인합니다.

메시지 파일을 직접 검사하거나 전체 자동 테스트를 실행할 수도 있습니다.

```sh
npm run lint:commit -- --edit <메시지파일경로>
npm run test:git
```

관련 문서: [브랜치명](branch-names.md) · [PR 제목](pull-request-titles.md)
