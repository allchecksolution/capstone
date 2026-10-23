## 1. 최초 환경 설정 및 Husky 활성화

### Git · Node.js · npm 준비

1. Git과 Node.js 24 이상을 설치합니다. npm은 Node.js 설치 시 함께 설치합니다.
2. 터미널을 다시 열고 설치 여부를 확인합니다.

```sh
git --version
node --version
npm --version
```

***

### 저장소 복제 및 의존성 설치

저장소 접근 권한을 받은 뒤 복제합니다. 이미 복제했다면 기존 저장소 폴더로 이동합니다.

```sh
git clone https://github.com/allchecksolution/capstone.git
cd capstone
npm ci
```

`npm ci`는 루트의 lockfile 기준으로 개발 의존성을 설치하고, `prepare` 스크립트(`node .husky/install.mjs`)를 실행해 Husky를 활성화합니다. **팀원마다 각자의 로컬
저장소에서 실행해야 합니다.** 전역 Husky 설치나 `husky init`은 필요하지 않습니다.

| 실행 시점        | 로컬 검사 내용                                 |
|--------------|------------------------------------------|
| `git commit` | `commit-msg` Hook이 Commitlint로 커밋 메시지 검사 |
| `git push`   | `pre-push` Hook이 원격에 올릴 대상 브랜치명 검사       |

PR 제목과 브랜치·PR의 Jira 키 일치 여부는 GitHub Actions에서 검사합니다.
***

### 활성화 확인 및 복구

```sh
git config --get core.hooksPath
npm run lint:branch
```

`core.hooksPath` 결과는 `.husky/_`여야 합니다. 브랜치 검사는 `main` 또는 컨벤션에 맞는 작업 브랜치에서 통과합니다.

의존성이 이미 설치되어 있고 Hook만 다시 활성화하려면 다음을 실행합니다.

```sh
npm run prepare
git config --get core.hooksPath
```

설치 시 `--ignore-scripts` 또는 `--omit=dev`를 사용하지 않습니다. 로컬 환경에 `HUSKY=0`, `CI`, `NODE_ENV=production`이 설정되어 있으면 설치 스크립트가
활성화를 건너뛰므로 해당 설정을 해제한 뒤 `npm ci`를 다시 실행합니다. 검사 실패 시 출력된 규칙을 확인하고 수정하며 Hook을 우회하지 않습니다.
***

## 2. Jira 이슈 및 작업 브랜치 준비

작업 전에 [브랜치명 컨벤션](docs/conventions/branch-names.md)을 확인합니다. **Jira 이슈 하나 : 작업 브랜치 하나 : PR 하나**를 유지하고, 브랜치와 PR 제목에 같은 Jira
키를 사용합니다.

1. Jira에서 작업 이슈를 발급하고 키를 확인합니다. 예: `ACK-12`.
2. 아래 명령으로 로컬 `main`을 갱신합니다. `git status`에 미완성 변경이 있다면 먼저 커밋하거나 `git stash push -u`로 임시 보관합니다. 임시 보관한 변경은 해당 작업 브랜치로 돌아온
   뒤 `git stash pop`으로 복원하고 충돌 여부를 확인합니다.

```sh
git status
git switch main
git pull origin main --rebase
```

3. Jira 이슈의 **브랜치 생성 기능**으로 저장소의 최신 `main`을 기준으로 브랜치를 생성합니다. 예: `feat/ACK-12-user-login`.
4. 생성된 원격 브랜치를 로컬로 가져옵니다.

```sh
git fetch origin
git switch --track origin/feat/ACK-12-user-login
npm run lint:branch
```

이미 로컬에 있는 작업 브랜치에서 이어서 작업한다면 다음을 사용합니다.

```sh
git switch feat/ACK-12-user-login
git pull --rebase
```

이 문서의 브랜치명과 Jira 키는 실제 작업에 맞게 바꿉니다. 같은 영역을 수정하는 이슈·PR이 있는지도 확인합니다.
***

## 3. 작업 및 커밋

코드나 문서를 수정한 뒤 변경 사항을 검토하고 필요한 테스트를 실행합니다. 커밋 메시지는 [커밋 메시지 컨벤션](docs/conventions/commit-messages.md)을 따릅니다.

```sh
git status
git diff
```

의도한 파일만 스테이징합니다. 아래 경로는 실제 변경한 파일 경로로 바꿉니다.

```sh
git add <변경한-파일-경로>
git diff --staged
git commit -m "feat(auth): 로그인 기능 구현"
```

커밋 시 Husky가 메시지를 검사합니다. 실패하면 메시지를 수정해 다시 커밋합니다. 비밀번호, API 키, 개인 로컬 설정이 포함되지 않았는지 확인합니다.

***

## 4. 최신 main 반영 및 Rebase 충돌 해결

PR을 생성하기 전 반드시 main의 최신 사항을 반영 , 충돌을 해결합니다

```sh
git status
git pull origin main --rebase
```

***

## 5. Push 및 PR 생성

원격에 처음 올리는 로컬 브랜치는 다음을 사용합니다.

```sh
git push -u origin feat/ACK-12-user-login
```

Jira에서 생성한 원격 브랜치를 추적 중이거나 이미 올린 브랜치는 `git push`로 갱신합니다. Push 시 Husky가 대상 브랜치명을 검사합니다.

이미 원격에 올린 커밋을 rebase하면 이력이 바뀌어 일반 push가 거절될 수 있습니다. **본인 작업 브랜치에 한해**, 다른 팀원이 원격에 추가한 변경이 없는지 확인한 후 실행합니다.

```sh
git push --force-with-lease
```

`--force-with-lease`가 거절되면 원격 변경을 먼저 확인하고 반영합니다. `--force`로 덮어쓰지 않으며, `main`에는 강제 push하지 않습니다. 다른 팀원이 함께 쓰는 브랜치의 이력 변경은
먼저 조율합니다.

PR 생성 절차는 다음과 같습니다.

1. GitHub 저장소에서 **Compare & pull request** 또는 **Pull requests → New pull request**를 엽니다.
2. **base: `main`**, **compare: 내 작업 브랜치**인지 확인합니다.
3. [PR 제목 컨벤션](docs/conventions/pull-request-titles.md)에 맞춰 제목을 작성합니다. 예: `feat(BE): 로그인 기능 구현 [ACK-12]`.
4. 자동으로 표시되는 [PR 템플릿](.github/pull_request_template.md)에 Jira 이슈, 변경 목적·내용, 검증 방법·결과, 참고 문서 및 리뷰 시 확인할 점을 작성합니다.
5. **Files changed**에서 의도한 변경만 포함되었는지 확인합니다.
6. 준비 중이면 Draft PR로, 리뷰 준비가 끝났다면 일반 PR로 생성하고 리뷰어를 지정합니다.
7. Git conventions의 **Branch name · PR title · Jira issue match · Validator tests** 결과를 확인하고 실패 원인을 수정합니다.

제목을 로컬에서 미리 검사할 수도 있습니다.

```sh
npm run lint:pr -- "feat(BE): 로그인 기능 구현 [ACK-12]"
```

리뷰 수정은 **같은 작업 브랜치**에서 수정 → 커밋 → push합니다. 기존 PR이 자동으로 갱신됩니다. 리뷰 및 검사가 완료되면 기존 컨벤션에 따라 **Squash Merge**하며, 최종 커밋 제목은 검증된
PR 제목을 유지합니다.

***

## 6. 병합 후 다음 작업 준비

```sh
git switch main
git pull origin main --rebase
```

다음 작업은 새로운 Jira 이슈와 해당 이슈의 새 작업 브랜치에서 시작합니다.

## 관련 컨벤션

- [브랜치명 및 Jira 연동](docs/conventions/branch-names.md)
- [커밋 메시지](docs/conventions/commit-messages.md)
- [PR 제목 및 Squash Merge](docs/conventions/pull-request-titles.md)
