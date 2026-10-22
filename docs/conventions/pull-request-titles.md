# PR 제목 컨벤션

PR 제목은 변경 내용을 설명하며, Squash Merge의 최종 커밋 제목으로 사용합니다.

## 형식

```text
<type>(<APP>): <description> [<Jira Issue Key>]
```

```text
feat(BE): JWT 기반 로그인 API 구현 [ACK-12]
fix(DRIVER): 위치 전송 오류 수정 [ACK-35]
chore(INFRA): GitHub Actions 설정 [ACK-42]
feat(MULTI): 로그인 API와 관리자 화면 연동 [ACK-51]
docs(COMMON): 팀 공통 협업 규칙 작성 [ACK-53]
```

## 항목별 규칙

| 항목 | 규칙 |
| --- | --- |
| `type` | `feat`, `fix`, `refactor`, `docs`, `test`, `chore` 중 하나 |
| `APP` | 아래 허용 목록에서 하나를 대문자로 작성. 필수 |
| `description` | 변경 내용을 한 줄로 작성. 한국어 허용. 비어 있거나 앞뒤 공백을 포함할 수 없음 |
| Jira Issue Key | 대문자 `ACK-숫자`를 제목 맨 끝에 대괄호로 표기. 소스 브랜치의 키와 일치해야 함 |

콜론 뒤와 `[ACK-숫자]` 앞에 공백을 하나씩 둡니다. 일반 커밋과 달리 APP과 Jira 키를 생략할 수 없으며, `build`, `perf`는 PR 제목의 type으로 허용하지 않습니다.

type은 기능 추가에 `feat`, 오류 수정에 `fix`, 구조 개선에 `refactor`, 문서 변경에 `docs`, 테스트 변경에 `test`, 설정·유지보수 등에 `chore`를 사용합니다.

## APP 선택

| APP | 사용 기준 |
| --- | --- |
| `BE` | 백엔드 변경 |
| `ADMIN` | 관리자 앱 변경 |
| `DRIVER` | 기사 앱 변경 |
| `AI` | AI 파트 변경 |
| `INFRA` | 인프라·배포·CI/CD 변경 |
| `MULTI` | 여러 파트의 구현을 함께 변경. 예: BE API와 ADMIN 화면 연동 |
| `COMMON` | 특정 파트에 속하지 않는 공통 설정·문서·자원 변경 |

여러 파트의 기능을 함께 수정하면 `MULTI`, 저장소 공통 규칙이나 자원을 수정하면 `COMMON`을 사용합니다. `BE,ADMIN`처럼 APP을 조합하지 않고 하나를 선택합니다. 공통 변경이어도 배포·CI/CD가 중심이면 `INFRA`가 적합합니다.

검사 코드는 APP의 허용 여부를 확인합니다. 실제 변경 파일과 APP 선택의 적절성은 리뷰에서 확인합니다.

## 브랜치와 Jira 키 맞추기

작업 단위의 Jira 이슈·작업 브랜치·PR은 **1:1:1로 대응**합니다. 세부 원칙은 [브랜치명 문서의 PR과의 관계](branch-names.md#pr과의-관계)를 따릅니다.

```text
브랜치: feat/ACK-51-admin-login
PR 제목: feat(MULTI): 로그인 API와 관리자 화면 연동 [ACK-51]
```

브랜치가 `ACK-51`이면 제목도 `[ACK-51]`로 끝나야 합니다. `main`을 소스 브랜치로 사용하는 경우 키 비교만 면제되며, 제목 형식은 그대로 검사합니다. Jira 키의 형식과 일치 여부를 검사하고 실제 이슈의 존재 여부는 조회하지 않습니다.

## 실패 사례

| 잘못된 예시 | 원인 |
| --- | --- |
| `feature(BE): 로그인 구현 [ACK-12]` | 허용되지 않는 type |
| `feat(be): 로그인 구현 [ACK-12]` | APP은 대문자 사용 |
| `feat(NULTI): 여러 앱 연동 [ACK-51]` | 올바른 식별자는 `MULTI` |
| `feat(BE,ADMIN): 로그인 연동 [ACK-51]` | APP 조합은 허용하지 않음 |
| `feat: 로그인 구현 [ACK-12]` | APP 누락 |
| `feat(BE):로그인 구현 [ACK-12]` | 콜론 뒤 공백 누락 |
| `feat(BE): 로그인 구현` | Jira 키 누락 |
| `feat(BE): 로그인 구현 [ACK-35]` | 브랜치가 `ACK-12`이면 Jira 키 불일치 |

## 검사 및 강제 방식

1. **PR 제목 검사**: GitHub Actions의 `Git conventions - PR title`이 형식, type, APP, description, Jira 키를 검사합니다.
2. **키 일치 검사**: `Git conventions - Jira issue match`가 소스 브랜치와 제목의 Jira 키를 비교합니다.
3. **재검사**: PR 생성, 제목·대상 수정, 새 커밋 Push, 다시 열기, 리뷰 준비 전환 시 실행합니다. 실패하면 구체적인 원인과 올바른 예시를 출력합니다.
4. **병합 제한**: 관리자가 두 검사를 `main` Ruleset의 필수 Status Check로 등록해야 실패한 PR의 병합을 차단할 수 있습니다. PR 템플릿의 체크박스는 작성 안내이며 검사를 대체하지 않습니다.

CI는 PR 내부의 개별 커밋 메시지를 검사하지 않습니다. 개별 커밋은 로컬 Husky + Commitlint가 검사하고, 최종 Squash 커밋 제목은 PR 제목 규칙을 따릅니다. CI의 `Git conventions - Validator tests`는 고정된 예시로 검증 로직과 Hook을 테스트합니다.

검사 로직은 [`scripts/git-conventions.cjs`](../../scripts/git-conventions.cjs)에 있으며 브랜치 검사와 허용 type을 공유합니다. PR 제목에는 로컬 Git Hook이 없으므로 미리 확인하려면 저장소 루트에서 실행합니다.

```sh
npm run lint:pr -- "feat(MULTI): 로그인 API와 관리자 화면 연동 [ACK-51]"
node scripts/git-conventions.cjs jira feat/ACK-51-admin-login "feat(MULTI): 로그인 API와 관리자 화면 연동 [ACK-51]"
```

## Squash Merge 시 확인

저장소 관리자는 Squash Merge의 기본 메시지를 **Pull request title**로 설정합니다. 병합하는 사람은 최종 커밋 제목에 검증된 PR 제목을 유지합니다. 병합 화면에서 사람이 수정한 최종 메시지까지 이 PR 검사가 검증하지는 않습니다.

관련 문서: [브랜치명](branch-names.md) · [커밋 메시지](commit-messages.md)
