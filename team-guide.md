# Capstone 팀 협업 가이드

이 문서는 `allchecksolution/capstone`에서 팀원이 **같은 Markdown 자료를 기준으로 개발**하고, 각자의 변경을 Pull Request(PR)로 `main`에 합치는 방법을 설명합니다. 공통으로 사용할 Markdown 파일은 아직 정하지 않았습니다. 정해지면 팀이 합의한 파일의 경로와 링크를 아래 「공통 문서 지정」 항목에 기록합니다.

## 핵심 규칙

1. `main`은 팀이 검토해 합친 기준 버전입니다. 작업은 `main`에서 새 브랜치를 만들어 진행합니다.
2. 공통 자료는 **원본 파일 하나**를 정해 참조합니다. 개인용 복사본을 만들고 각각 수정하지 않습니다.
3. 문서 변경도 코드 변경처럼 PR로 제안하고 검토합니다. 다른 사람의 내용을 확인 없이 덮어쓰지 않습니다.
4. 작업 중 `main`의 공통 문서가 바뀌면 최신 내용을 확인하고, 내 코드가 여전히 맞는지 다시 점검합니다.

GitHub 저장소는 실시간 공동 편집 문서가 아닙니다. 각자 브랜치에서 작업하고, PR의 변경 비교와 리뷰를 통해 하나의 `main`으로 합칩니다. 동시에 같은 파일을 수정할 수 있지만 병합 충돌이나 의미상 모순이 생길 수 있으므로 아래 절차를 따릅니다.

## 처음 참여하기

이 저장소는 비공개입니다. 팀원은 저장소 접근 권한이 필요하고, 브랜치를 올리려면 `Write` 권한이 필요합니다. 저장소 관리자는 **Settings → Collaborators & teams**에서 사람이나 팀의 권한을 확인합니다.

각자 작업할 폴더에서 한 번만 복제합니다.

```powershell
git clone https://github.com/allchecksolution/capstone.git
cd capstone
```

이미 복제했다면 다시 `clone`하지 않습니다. 이후에는 `git pull`로 갱신합니다. Git 명령을 처음 쓰는 팀원은 GitHub Desktop을 사용해도 되지만, 브랜치 → 커밋 → 푸시 → PR 순서는 같습니다.

## 공통 문서 지정

팀이 기준으로 삼을 Markdown 파일을 정한 뒤 아래 정보를 **이 가이드의 PR에서** 채웁니다. 그 전에는 특정 파일을 공식 기준으로 간주하지 않습니다.

| 항목 | 팀이 결정할 내용 |
| --- | --- |
| 공통 문서 경로 | 미정 |
| `main`의 문서 링크 | 미정 |
| 문서 관리 담당 또는 리뷰어 | 미정 |
| 변경 시 함께 확인할 코드·자료 | 미정 |

공통 문서가 정해지면 다음처럼 사용합니다.

- **현재 기준**을 볼 때는 GitHub의 `main` 브랜치에 있는 원본 파일을 엽니다. 개발 시작 전 `main`을 갱신해 같은 버전을 읽습니다.
- 코드 주석이나 다른 Markdown에서 내용을 다시 복사하지 말고 **원본으로 연결**합니다. 같은 저장소의 Markdown끼리는 `[공통 문서](상대/경로.md)`처럼 실제 위치에 맞는 상대 경로 링크를 씁니다. 링크가 현재 브랜치에서도 열리는지 확인합니다.
- PR에서 “어느 버전을 보고 개발했는지” 고정해서 남겨야 할 때는 GitHub 파일 화면의 **Copy permalink**로 커밋에 고정된 링크를 복사합니다. `main` 파일 링크는 최신 내용을 가리키고, permalink는 당시 내용을 가리킵니다.
- 문서 안의 항목을 가리켜야 한다면 제목 링크 또는 팀이 합의한 고유 식별자를 사용합니다. 제목을 바꾸거나 항목을 이동할 때 연결된 링크도 확인합니다.
- 외부 자료가 원본이라면 모두가 접근 가능한 공유 링크를 공통 문서에 기록합니다. 접근할 수 없는 링크를 기준 자료로 삼지 않습니다.

## 내 작업을 시작하는 순서

작업 전에 누가 같은 부분을 수정하는지 Issue나 PR 목록을 확인합니다. 같은 문단을 둘 이상 고쳐야 한다면 먼저 담당 범위와 순서를 합의합니다. 읽고 개발하는 사람은 공통 문서를 수정할 필요가 없습니다.

작업 폴더에 미완성 변경이 없다면 다음을 실행합니다. `feature/my-task`는 내 작업을 나타내는 **새 이름**으로 바꿉니다.

```powershell
git status
git switch main
git pull origin main
git switch -c feature/my-task
```

`git status`에 내가 저장하지 않은 변경이 있다면 먼저 그 변경의 처리 방법을 정하고 브랜치를 전환합니다. 예시 브랜치 이름이 이미 존재하면 다른 이름을 사용합니다. 코드만 작성하는 경우 공통 문서는 읽기만 하고, 코드 변경의 근거를 PR에 연결합니다.

## 공통 문서를 망가뜨리지 않고 수정하기

공통 문서 자체를 바꿔야 할 때는 **문서의 작은 범위**를 한 PR에서 수정합니다. 파일 전체를 다시 만들거나 자동 정렬해서 관계없는 줄까지 바꾸지 않습니다.

1. `main`의 최신 문서를 읽고, 바꿀 부분이 다른 사람의 PR에서 이미 작업 중인지 확인합니다.
2. 내 브랜치에서 필요한 문단만 수정합니다. 기존 합의나 설명을 삭제한다면 PR 본문에 이유와 대체 내용을 적습니다.
3. `git diff`로 삭제·변경된 줄을 검토합니다. 잘못 지운 내용이 없는지, 상대 경로 링크와 이미지가 열리는지 확인합니다.
4. 문서가 코드 동작의 기준을 바꾼다면 관련 코드도 같은 PR에서 수정하거나, 이어서 수정할 Issue/PR을 명시합니다. 기준과 구현이 서로 어긋난 채 합쳐지지 않도록 리뷰어에게 알립니다.
5. 공통 문서 담당자 또는 해당 내용을 아는 팀원의 리뷰를 받습니다. PR의 **Files changed**에서 문서 변경을 한 줄씩 확인한 뒤 병합합니다.

변경 전 파일을 따로 복사해 저장할 필요는 없습니다. Git이 이전 커밋의 내용을 보관하므로 변경 이력과 PR에서 비교할 수 있습니다. 다만 Git의 이력은 실수 방지 절차를 대신하지 않으므로 리뷰를 거칩니다.

## 커밋하고 브랜치 올리기

수정 후 변경 파일을 확인합니다. 아래 명령의 `git add .`는 현재 폴더 아래의 변경을 모두 추가하므로, **반드시** `git status`와 `git diff --cached`로 포함된 파일을 확인합니다.

```powershell
git status
git diff
git add .
git diff --cached
git commit -m "작업 내용을 설명하는 짧은 문장"
git push -u origin feature/my-task
```

`git push`의 브랜치 이름도 실제 내 브랜치 이름으로 바꿉니다. 비밀번호, API 키, 개인 정보, 로컬 설정 파일은 커밋하지 않습니다. 이미 올린 브랜치에 수정 내용을 더할 때는 다시 커밋한 뒤 `git push`를 실행합니다.

## `main`으로 Pull Request 보내기

1. GitHub에서 `allchecksolution/capstone` 저장소를 엽니다.
2. 푸시 직후 보이는 **Compare & pull request**를 누릅니다. 보이지 않으면 **Pull requests → New pull request**를 누릅니다.
3. **base: `main`**, **compare: 내 작업 브랜치**인지 확인합니다. 방향이 반대면 변경 비교가 달라집니다.
4. **Files changed**를 열어 의도하지 않은 파일, 공통 문서의 대량 삭제, 깨진 링크가 없는지 확인합니다.
5. 제목과 본문을 작성합니다. 본문에는 **왜 바꿨는지, 무엇을 바꿨는지, 어떻게 확인했는지, 참고한 공통 문서의 링크 또는 버전, 문서와 코드가 함께 바뀌었는지**를 적습니다. 관련 Issue가 있다면 연결합니다.
6. 작업 중임을 먼저 알릴 때는 **Draft pull request**, 검토를 받을 준비가 됐다면 **Create pull request**를 선택합니다. 리뷰어를 지정하고 의견을 반영합니다.
7. 수정 요청을 받으면 **같은 브랜치**에서 수정 → 커밋 → 푸시합니다. 기존 PR이 자동으로 갱신됩니다. 검토가 끝나고 충돌이 없을 때 `main`에 병합합니다.

PR 본문 예시:

```text
목적: 이 작업이 필요한 이유
변경: 수정한 코드와 문서의 핵심 내용
공통 문서: 원본 링크 또는 확인한 버전의 permalink (정해진 뒤 기입)
확인: 실행한 테스트, 화면 확인, 링크 확인 등
함께 검토할 점: 문서 변경이 다른 작업에 미치는 영향
```

브라우저에서 Markdown만 고치는 경우에도 `main`에서 바로 저장하기보다 파일 편집 화면에서 **새 브랜치에 커밋**을 선택하고 PR을 만듭니다.

## 다른 사람이 먼저 변경했을 때

PR을 여는 동안 다른 PR이 `main`에 합쳐졌다면 최신 기준을 확인합니다. GitHub PR 화면에서 **Update branch**가 제공되고 충돌이 없다면 이를 이용할 수 있습니다. 로컬에서 갱신하려면 **내 작업 브랜치에 있는 상태에서** 다음을 실행합니다.

```powershell
git fetch origin
git merge origin/main
```

충돌이 나면 Git이 표시한 파일을 열어 두 사람의 의도를 비교하고, 최종 문장을 팀원과 결정합니다. `<<<<<<<`, `=======`, `>>>>>>>` 표시를 제거한 뒤 파일 내용·링크·코드 동작을 확인하고 커밋·푸시합니다. 상대의 내용을 무조건 삭제하거나 `git push --force`로 덮어쓰지 않습니다. **Git이 자동 병합해도 의미가 충돌할 수 있으므로**, 공통 문서와 코드가 여전히 같은 기준을 따르는지 PR에서 검토합니다.

병합 후 다음 작업을 시작할 때는 로컬 `main`을 갱신합니다.

```powershell
git switch main
git pull origin main
```

## 저장소 관리자에게 권장하는 설정

`main`에 직접 푸시하지 않고 PR을 거치도록 저장소의 브랜치 보호 규칙 또는 Ruleset을 설정할 수 있습니다. 가능하면 **PR 필수**와 **최소 1명 승인**을 적용해 공통 문서 변경이 리뷰 없이 합쳐지는 일을 줄입니다. 설정 가능 항목은 저장소의 공개 범위와 GitHub 요금제에 따라 다를 수 있습니다.

## 참고 문서

- [GitHub: Pull Request 만들기](https://docs.github.com/en/pull-requests/how-tos/create-pull-requests/creating-a-pull-request)
- [GitHub: PR 브랜치를 `main`과 동기화하기](https://docs.github.com/en/pull-requests/how-tos/create-pull-requests/keeping-your-pull-request-in-sync-with-the-base-branch)
- [GitHub: Markdown 상대 경로 링크](https://docs.github.com/en/repositories/managing-your-repositorys-settings-and-features/customizing-your-repository/about-readmes#relative-links-and-image-paths)
- [GitHub: 브랜치 보호](https://docs.github.com/en/repositories/configuring-branches-and-merges-in-your-repository/managing-protected-branches/about-protected-branches)
