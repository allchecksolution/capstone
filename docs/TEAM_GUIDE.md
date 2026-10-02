# 팀 사용설명서

이 문서는 `allchecksolution/capstone` 저장소에서 문서와 코드를 함께 수정하는 방법입니다.

## 1. 처음 준비하기

이 저장소는 비공개입니다. 저장소 관리자가 팀원에게 접근 권한을 부여해야 복제하고 작업할 수 있습니다. GitHub의 저장소 **Settings → Collaborators & teams**에서 사람이나 팀을 추가하고, 코드를 올릴 팀원에게 `Write` 권한을 부여합니다.

Git을 설치하고 GitHub 계정으로 인증한 뒤 저장소를 복제합니다. 각자 원하는 작업 폴더에서 실행하세요.

```powershell
git clone https://github.com/allchecksolution/capstone.git
cd capstone
```

이미 복제한 팀원은 다시 `clone`하지 않습니다. 아래 절차로 최신 내용을 받습니다.

## 2. 작업 시작하기

작업마다 브랜치 하나를 만들고, 무엇을 바꾸는지 알 수 있는 이름을 붙입니다. 예: `docs/requirements`, `design/login-screen`, `feature/login`.

```powershell
git switch main
git pull origin main
git switch -c docs/requirements
```

`main`은 팀이 합친 결과를 보는 기준 브랜치입니다. 새 기능과 문서 수정은 작업 브랜치에서 진행합니다. 같은 브랜치 이름이 이미 있다면 다른 이름을 사용하세요.

## 3. 수정 내용을 GitHub에 올리기

파일을 수정한 뒤 바뀐 내용을 확인하고 커밋합니다.

```powershell
git status
git add docs/requirements.md
git commit -m "docs: 요구사항 초안 작성"
git push -u origin docs/requirements
```

코드를 수정했다면 `git add` 뒤에 해당 파일 경로를 적습니다. 비밀번호, API 키, 개인 정보, 로컬 설정 파일은 커밋하지 않습니다. 이미 올린 브랜치에 수정 사항을 더할 때는 다시 커밋한 후 `git push`를 실행합니다.

## 4. `main`으로 Pull Request 보내기

1. GitHub의 `allchecksolution/capstone` 저장소를 엽니다.
2. 브랜치를 올린 뒤 나타나는 **Compare & pull request**를 누릅니다. 배너가 보이지 않으면 **Pull requests → New pull request**를 누릅니다.
3. **base**가 `main`, **compare**가 내가 작업한 브랜치인지 확인합니다.
4. 제목에는 변경 내용을, 본문에는 변경 이유·주요 수정 사항·확인한 내용을 적습니다. 관련 Issue가 있다면 번호도 연결합니다.
5. **Create pull request**를 누르고 팀원에게 검토를 요청합니다. 의견이 오면 같은 브랜치에서 수정·커밋·푸시합니다. Pull Request에 수정 내용이 반영됩니다.
6. 검토가 끝나면 `main`에 병합합니다. 병합 뒤에는 로컬 `main`을 다시 갱신합니다.

```powershell
git switch main
git pull origin main
```

작업 중 `main`에 다른 변경이 합쳐졌다면 Pull Request 화면의 **Update branch**를 이용할 수 있습니다. 충돌이 나면 서로의 수정을 확인하고 최종 내용을 정한 뒤 해결합니다.

## 5. 같은 자료를 기준으로 개발하기

[공통 자료 목록](resources.md)을 자료의 시작점으로 사용합니다. 팀이 확정한 요구사항은 [요구사항 문서](requirements.md), 화면별 내용은 [화면 설계 문서](screen-design.md)에 기록합니다. 채팅이나 개인 파일에만 최신 결정을 남기지 말고, 해당 Markdown 문서를 수정하는 Pull Request를 엽니다.

- 한 항목의 **공식 위치**를 하나 정합니다. 같은 요구사항을 여러 문서에 복사하지 않고 링크로 연결합니다.
- 요구사항에는 `REQ-001`처럼 고유 ID를 붙입니다. 화면 설계와 코드 관련 Pull Request에서 그 ID를 언급하면 어떤 요구사항을 구현했는지 추적할 수 있습니다.
- 문서가 아직 합의 전이라면 `초안`이라고 표시합니다. 중요한 변경은 Pull Request에서 팀원 검토를 받습니다.
- 이미지·다이어그램을 저장소에 넣을 경우 문서 옆의 `assets/` 폴더에 두고 상대 경로로 연결합니다. 외부 디자인 도구를 쓴다면 [공통 자료 목록](resources.md)에 공유 링크와 접근 방법을 기록합니다.

Markdown 파일끼리는 상대 경로로 연결합니다. 예를 들어 이 파일에서 요구사항으로 연결할 때는 `[요구사항](requirements.md)`를 씁니다. 이렇게 하면 브랜치에서도 해당 브랜치의 문서를 참조할 수 있습니다.

## 참고

- [GitHub: Pull Request 만들기](https://docs.github.com/en/pull-requests/how-tos/create-pull-requests/creating-a-pull-request)
- [GitHub: Markdown 상대 경로 링크](https://docs.github.com/en/repositories/managing-your-repositorys-settings-and-features/customizing-your-repository/about-readmes#relative-links-and-image-paths)
