## PR 제목 작성 규칙

```text
<type>(<APP>): <description> [ACK-숫자]
예: feat(MULTI): 로그인 API와 관리자 화면 연동 [ACK-51]
```

- **type**: `feat` 기능, `fix` 오류, `refactor` 구조 개선, `docs` 문서, `test` 테스트, `chore` 설정·유지보수 중 하나.
- **APP**: `BE`, `ADMIN`, `DRIVER`, `AI`, `INFRA`, `MULTI`, `COMMON` 중 하나를 대문자로 작성. `MULTI`는 여러 파트의 구현 변경, `COMMON`은 공통 설정·문서·자원 변경에 사용합니다.
- **description**: 변경 내용을 한 줄로 작성하고 콜론 뒤와 Jira 대괄호 앞에 공백을 하나씩 둡니다.
- **Jira 키**: 소스 브랜치의 키와 동일하게 제목 끝에 작성합니다. 예: `feat/ACK-51-admin-login` → `[ACK-51]`.
- APP과 Jira 키는 필수이며, 검증된 제목을 Squash Merge의 최종 커밋 제목으로 유지합니다.

상세 기준: [PR 제목 컨벤션](https://github.com/allchecksolution/capstone/blob/main/docs/conventions/pull-request-titles.md)

## 목적 및 Jira 이슈

ACK-숫자:

## 변경 사항

-

## 검증 방법 및 결과

-

## 참고 문서 및 리뷰 시 확인할 점

공통 문서 링크/버전, 문서와 코드의 동시 변경 여부:

## 확인

- [ ] 이 Jira 이슈에 대응하는 작업 브랜치와 PR이 각각 하나인지 확인했습니다.
- [ ] PR 제목 형식과 브랜치의 Jira Issue Key가 일치합니다.
- [ ] 로컬 Husky + Commitlint로 개별 커밋 메시지 컨벤션을 확인했습니다.
- [ ] Git conventions의 Branch name, PR title, Jira issue match, Validator tests가 모두 통과했습니다.
