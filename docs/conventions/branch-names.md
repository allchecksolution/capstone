# 브랜치명 컨벤션

`main`에서 작업 브랜치를 만들고, 작업이 끝나면 PR을 통해 Squash Merge합니다.

## 브랜치 생성 절차

1. 작업을 시작하기 전에 Jira에서 이슈를 발급하고 이슈 키를 확인합니다. 예: `ACK-12`.
2. 해당 Jira 이슈의 **브랜치 생성 기능**을 이용해 프로젝트 저장소의 `main`을 기준으로 작업 브랜치를 생성합니다.
3. 브랜치명은 아래 컨벤션에 따라 **해당 이슈 키를 포함**하여 작성합니다. 예: `feat/ACK-12-user-login`. 생성 화면에서 제안한 이름도 컨벤션에 맞는지 확인하고, 다르면 생성 전에 수정합니다.

생성된 브랜치를 로컬로 가져와 작업하고, 같은 Jira 이슈 키를 사용하는 PR을 작성합니다.

## 형식

```text
<type>/<Jira Issue Key>-<description>
```

| 항목 | 규칙 |
| --- | --- |
| `type` | `feat`, `fix`, `refactor`, `docs`, `test`, `chore` 중 하나 |
| Jira Issue Key | 대문자 `ACK-숫자`. 예: `ACK-12` |
| `description` | 소문자 영문·숫자로 된 단어를 하이픈으로 연결 |

`description`은 필수이며 공백, 한글, 대문자, 밑줄, 연속 하이픈, 앞뒤 하이픈을 허용하지 않습니다. `main`만 브랜치명 형식의 예외입니다. `develop`, `feature/`, `hotfix/`는 사용하지 않습니다.

## type 선택

| type | 용도 |
| --- | --- |
| `feat` | 기능 추가 |
| `fix` | 오류 수정 |
| `refactor` | 기능 변경 없이 구조 개선 |
| `docs` | 문서 작성·수정 |
| `test` | 테스트 추가·수정 |
| `chore` | 설정·유지보수 등 기타 작업 |

커밋에서 허용하는 `build`, `perf`는 브랜치명과 PR 제목에서는 허용하지 않습니다.

## 예시

```text
feat/ACK-12-user-login
fix/ACK-35-dispatch-error
chore/ACK-42-project-initial-setup
docs/ACK-53-team-conventions
```

| 잘못된 예시 | 원인 |
| --- | --- |
| `feature/ACK-12-user-login` | 허용되지 않는 type |
| `feat/user-login` | Jira 키 누락 |
| `feat/ACK-12` | description 누락 |
| `feat/ACK-12-UserLogin` | description에 대문자 사용 |
| `feat/ACK-12-user_login` | 단어 구분에 밑줄 사용 |

브랜치에는 APP을 넣지 않습니다. 여러 파트에 걸친 변경도 같은 형식을 사용하고, PR 제목에서 `MULTI` 또는 `COMMON`을 선택합니다.

## PR과의 관계

작업 단위의 Jira 이슈 하나에 작업 브랜치 하나와 PR 하나를 **1:1:1로 대응**시킵니다.

- 작업 브랜치와 PR은 동일한 Jira 이슈 키를 사용합니다.
- 하나의 Jira 이슈를 여러 작업 브랜치나 PR로 나누어 진행하지 않습니다.
- 여러 PR로 나눠야 하는 작업은 Jira 이슈도 각 작업 단위로 분리합니다.
- PR 리뷰에 따른 수정은 기존 브랜치에 추가 커밋하여 같은 PR에 반영합니다.
- 하나의 브랜치에는 여러 커밋을 작성할 수 있습니다.

이 원칙은 작업 브랜치에 적용하며, 공용 기준 브랜치인 `main`은 제외합니다.

브랜치와 PR 제목의 Jira 키는 같아야 합니다.

```text
브랜치: feat/ACK-12-user-login
PR 제목: feat(BE): JWT 기반 로그인 API 구현 [ACK-12]
```

`[ACK-35]`처럼 다른 키를 사용하면 검사에 실패합니다. `main`은 비교할 Jira 키가 없어 키 비교만 면제되며, PR 제목 형식 검사는 그대로 적용됩니다.

현재 CI는 해당 PR의 브랜치와 제목에 있는 Jira 키가 같은지 검사합니다. 다른 브랜치나 PR에서 같은 키를 중복 사용했는지는 검사하지 않으므로, **1:1:1 관계는 작성자와 리뷰어가 확인하는 협업 규칙**입니다.

## 검사 및 강제 방식

1. **로컬 Push**: 저장소 루트에서 최초 `npm ci`를 실행하면 Husky가 활성화됩니다. `.husky/pre-push`가 공통 검사 스크립트를 실행하고, 실패하면 Push를 중단합니다.
2. **실제 대상 검사**: Git이 전달한 원격 대상 ref를 검사하므로 다른 브랜치를 Push하거나 `HEAD:브랜치명`, 여러 ref를 지정해도 적용됩니다. 태그와 브랜치 삭제는 검사에서 제외합니다.
3. **PR 검사**: GitHub Actions의 `Git conventions - Branch name`이 PR 소스 브랜치를 검사하고, `Git conventions - Jira issue match`가 제목과 Jira 키를 비교합니다. 실패 시 원인과 올바른 예시를 출력합니다.
4. **병합 제한**: 관리자가 두 검사를 `main` Ruleset의 필수 Status Check로 등록해야 실패한 PR의 병합을 차단할 수 있습니다. 로컬 Hook은 우회 가능하므로 CI와 함께 사용합니다.

브랜치 생성 시점에는 자동 검사하지 않습니다. Push 전에 직접 확인하려면 저장소 루트에서 실행합니다.

```sh
npm run lint:branch
npm run lint:branch -- feat/ACK-12-user-login
```

규칙의 구현은 [`scripts/git-conventions.cjs`](../../scripts/git-conventions.cjs)에 있습니다. Jira 키의 형식과 일치 여부를 검사하며 실제 Jira 이슈의 존재 여부는 조회하지 않습니다.

관련 문서: [PR 제목](pull-request-titles.md) · [커밋 메시지](commit-messages.md)
