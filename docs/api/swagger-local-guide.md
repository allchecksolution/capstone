# 팀원용 Swagger 로컬 실행 가이드

Windows 11의 PowerShell과 macOS의 터미널(zsh)에서 저장소를 받아 API 명세를 Swagger UI로 보고, Mock 서버로 요청·응답을 점검하는 방법이다. 이 가이드의 변경 사항이 포함된 브랜치 또는 병합된 `main`을 받아야 한다. 명령은 별도 표시가 없으면 두 OS에서 같다.

## 지금 할 수 있는 것과 없는 것

현재 OpenAPI(`docs/api/openapi.yaml`)는 UC-01~16에 필요한 관리자 앱·기사 앱 API의 **설계 초안**이다. 백엔드 컨트롤러는 아직 구현되지 않았다. 그래서 점검 방식은 두 가지다.

| 하고 싶은 일 | 방법 | 필요한 것 |
|---|---|---|
| 명세를 읽고 구조를 확인 | Swagger UI (Docker) | Docker Desktop |
| 구현 없이 요청·응답을 눈으로 확인 | Swagger UI + Prism Mock | Docker Desktop, Node.js |
| 실제 업무 로직·DB·권한 동작을 확인 | Spring Boot 백엔드 | 컨트롤러 구현 후. [부록](#부록-백엔드와-db-실행구현-후) 참고 |

Mock이 돌려주는 값은 명세에 적힌 `example`이다. 실제 업무 데이터가 아니며 업무 규칙, 권한, 멱등성 처리, 동시성은 확인하지 못한다. GitHub에 코드를 올리는 것만으로 공용 Swagger 서버가 생기지는 않는다. 팀원은 각자 PC에서 실행한다.

처음 설정하는 팀원은 1~3장을 따른다. 이미 설정을 마치고 컴퓨터를 껐다 켠 뒤 다시 작업하는 팀원은 [6장](#6-컴퓨터를-껐다-켠-뒤-매일-작업-순서)만 따른다.

## 1. 준비할 프로그램

| 프로그램 | 용도 | 확인 명령 |
|---|---|---|
| Git | 저장소 받기·변경 공유 | `git --version` |
| Docker Desktop | Swagger UI 실행 | `docker info`, `docker compose version` |
| Node.js 24 이상·npm | Mock 서버, 명세 lint, 팀 Git Hook | `node --version`, `npm --version` |

백엔드 실행에 필요한 JDK 21과 PostgreSQL은 [부록](#부록-백엔드와-db-실행구현-후)에서만 사용한다. 최초 실행 시 이미지·패키지 다운로드를 위한 인터넷 연결이 필요하다.

### Windows 준비

PowerShell에서 설치한다. Docker Desktop은 설치 후 PC를 재시작해야 할 수 있다. Node.js는 24 이상이 필요하므로 설치된 버전이 24 미만이면 [공식 다운로드](https://nodejs.org/en/download)에서 직접 설치한다.

```powershell
winget install --id Git.Git --exact
winget install --id OpenJS.NodeJS --exact
winget install --id Docker.DockerDesktop --exact
```

Docker Desktop을 실행하고 Linux 컨테이너 엔진이 시작된 뒤 `docker info`에 Server 정보가 출력되는지 확인한다. WSL 기반이라면 [WSL 2 설정 안내](https://docs.docker.com/desktop/features/wsl/)를 참고한다.

### macOS 준비

1. [Docker Desktop for Mac](https://docs.docker.com/desktop/setup/install/mac-install/)에서 칩 종류(Apple Silicon 또는 Intel)에 맞는 파일을 설치하고 앱을 실행한다. 칩 종류는 Apple 메뉴의 **이 Mac에 관하여**에서 확인한다.
2. Git과 Node.js 24 이상을 준비한다. `git --version`을 실행했을 때 개발 도구 설치 안내가 나오면 완료한다.

[Homebrew](https://brew.sh)를 쓰면 아래 명령으로 설치할 수 있다. Node.js 버전은 `node --version`으로 24 이상인지 확인한다.

```bash
brew install --cask docker
brew install git node
```

## 2. 저장소 받기

```sh
git clone https://github.com/allchecksolution/capstone.git
cd capstone
npm ci
```

이미 복제한 팀원은 다시 clone하지 않는다. 작업 중인 변경을 먼저 확인하고, 이 설정이 포함된 브랜치의 최신 변경을 받는다. 아직 `main`에 병합되지 않았다면 공유받은 브랜치를 체크아웃한다. `npm ci`는 팀의 커밋·브랜치 검사 Hook을 설치한다. 상세 규칙은 [팀 협업 가이드](../../team-guide.md)를 따른다.

이후 **프로젝트 루트**는 아래 파일들이 있는 `capstone` 폴더다. 명령은 모두 이 폴더에서 실행한다. 다른 폴더에서 실행하면 `docs/api/openapi.yaml`을 찾지 못해 `ENOENT` 오류가 난다.

```text
capstone/
├─ apps/backend/
├─ docs/api/openapi.yaml
├─ infra/compose/compose.docs.yml    ← Swagger UI
├─ infra/compose/compose.local.yml   ← PostgreSQL (부록)
└─ package.json
```

## 3. Swagger UI와 Mock 서버 실행

터미널 두 개를 사용한다. Docker Desktop이 실행 중이어야 한다.

**터미널 A: Swagger UI 시작** (최초 1회. 이후에는 Docker Desktop이 켜지면 컨테이너가 자동으로 다시 시작된다.)

```sh
npm run docs:up
```

**터미널 B: Mock 서버 시작** (사용하는 동안 열어 둔다. 종료는 `Ctrl+C`.)

```sh
npm run mock
```

시작 로그에 `Prism is listening on http://127.0.0.1:4010`이 나오면 준비된 것이다. 첫 실행에는 패키지 다운로드가 필요하다. Node.js 버전 경고(`EBADENGINE`)가 나와도 동작하지만 24 이상을 권장한다.

각 명령의 의미는 다음과 같다.

| 명령 | 하는 일 |
|---|---|
| `npm run docs:up` | Swagger UI 컨테이너 시작 (`http://localhost:8081`) |
| `npm run docs:down` | Swagger UI 컨테이너 종료 |
| `npm run mock` | Prism Mock 서버 시작 (`http://127.0.0.1:4010`) |
| `npm run lint:api` | 명세 구조 검사 |

## 4. Swagger UI에서 API 점검

1. 브라우저에서 <http://localhost:8081>을 연다. 로그인 화면은 없다.
2. 상단 **Servers** 목록에서 `http://127.0.0.1:4010`(Prism Mock 서버)을 선택한다. 기본값은 첫 번째 항목인 `api.example.invalid`이며, 이 주소는 존재하지 않아 그대로 실행하면 `Failed to fetch`가 나온다.
3. 로그인 계열 API(`/admin/auth/*`, `/driver/auth/kakao`, `/auth/token/refresh`)는 인증이 필요 없다. 나머지 API는 **Authorize**에 임의의 Bearer 값(예: `test`)을 입력한다. Mock은 값이 맞는지가 아니라 인증 헤더가 있는지만 확인한다. 입력한 값은 새로고침해도 유지된다.
4. 원하는 API에서 `Try it out`을 누르고 필수 파라미터와 헤더(`Idempotency-Key` 등)를 채운 뒤 `Execute`를 누른다.

### 무엇을 보고 판단하나

| 확인할 것 | 방법 |
|---|---|
| 요청이 명세와 맞는지 | 필수 필드·헤더를 빼면 `422`가 오는지 확인 |
| 인증이 필요한 API인지 | `Authorize`를 비우면 `401`이 오는지 확인 |
| 화면에 필요한 데이터가 응답에 있는지 | 응답 본문을 [화면별 대응표](screen-api-mapping.md)의 화면과 비교 |
| 예외 흐름(UC의 E1, E2…)에 대응하는 응답이 있는지 | 각 API의 Responses 목록과 예시 확인 |

### 오류·다른 예시 응답 확인

Mock은 기본으로 첫 번째 성공 예시를 돌려준다. 오류나 다른 예시를 보려면 요청에 `Prefer` 헤더를 붙인다. Swagger UI에서는 임의 헤더를 넣기 어려우므로 명령이나 Postman을 사용한다.

| 헤더 | 효과 | 예 |
|---|---|---|
| `Prefer: code=409` | 해당 상태 코드의 응답 | 취소 요청 이미 처리됨 |
| `Prefer: example=noData` | 이름이 `noData`인 예시 응답 | 통계 데이터 없음 |
| `Prefer: code=409, example=versionConflict` | 코드와 예시 이름 조합 | 버전 충돌 |

예시 이름은 `openapi.yaml`의 `examples` 키(`empty`, `noData`, `versionConflict` 등)다. Windows PowerShell에서는 `curl`이 다른 명령의 별칭이므로 `curl.exe`를 사용한다. Mock 서버의 경로에는 `/api/v1`이 붙지 않는다.

```sh
curl -i -H "Authorization: Bearer test" -H "Prefer: example=noData" "http://127.0.0.1:4010/work-statistics?from=2026-09-01&to=2026-09-30"
```

[Postman](https://www.postman.com)에서는 `openapi.yaml`을 Import하면 모든 API가 컬렉션으로 만들어지고 헤더를 화면에서 추가할 수 있다. 서버 주소 변수를 `http://127.0.0.1:4010`으로 지정한다.

### 점검 예시: 관리자 로그인부터 접수 확정까지

1. `POST /admin/auth/login` → `nextStep: MFA_REQUIRED`와 `challengeToken`
2. `POST /admin/auth/mfa/verify` → 액세스 토큰. 이 값을 `Authorize`에 넣을 필요는 없지만 실제 구현 후에는 이 값을 쓴다.
3. `GET /receptions` → 접수 목록. `GET /receptions/{receptionId}`에서 `version` 확인
4. `PATCH /receptions/{receptionId}` → 수정 저장. 응답의 `version`
5. `POST /receptions/{receptionId}/confirmation` → 확정. 요청의 `expectedVersion`에 4번의 `version`을 사용

각 단계에서 이전 응답의 값이 다음 요청에 쓰이는지, 값을 어디서 얻는지 명세만으로 알 수 있는지 확인한다. 막히는 지점이 명세가 부족한 곳이다.

## 5. 명세 수정과 반영

수정할 파일은 **`docs/api/openapi.yaml` 원본 하나**다. 화면과 업무 규칙이 바뀌면 [화면별 대응표](screen-api-mapping.md)와 [업무 규칙](business-rules.md)도 함께 검토한다.

| 수정 후 할 일 | 방법 |
|---|---|
| Swagger UI에 반영 | 파일 저장 후 브라우저 강력 새로고침(Windows `Ctrl+Shift+R`, macOS `Cmd+Shift+R`). 컨테이너 재시작 불필요 |
| Mock 응답에 반영 | 터미널 B에서 `Ctrl+C`로 종료하고 `npm run mock` 다시 실행. Mock은 시작할 때 명세를 한 번만 읽는다 |
| 구조 검사 | `npm run lint:api` |

`npm run lint:api`가 오류 없이 끝나도 실제 백엔드의 동작·권한·동시성 테스트가 통과했다는 뜻은 아니다. 현재 경고 1건(`DriverApprovalRequest`의 `rejectionReason`)은 린터의 오탐으로 알려져 있다. [Redocly CLI 안내](https://redocly.com/docs/cli/installation)를 참고한다.

`servers`의 `http://127.0.0.1:4010` 항목은 Mock 확인용이다. 백엔드를 구현하면 실제 개발 서버 주소로 바꾸거나 제거한다.

## 6. 컴퓨터를 껐다 켠 뒤 매일 작업 순서

최초 설치(1~2장)는 다시 하지 않는다.

1. **Docker Desktop 실행** 후 엔진이 시작될 때까지 기다린다.
2. **터미널 A(프로젝트 루트)**에서 최신 코드를 받는다. 작업 중인 변경이 있으면 먼저 커밋하거나 보관한다.

```sh
git pull
npm run docs:up
```

`package.json`이나 `package-lock.json`이 바뀌었다면 `npm ci`를 다시 실행한다. 이미 컨테이너가 실행 중이면 `docs:up`을 반복해도 안전하다.

3. **터미널 B(프로젝트 루트)**에서 Mock을 시작한다.

```sh
npm run mock
```

4. <http://localhost:8081>을 열고 Servers에서 `http://127.0.0.1:4010`을 선택한다.

### 작업 종료

1. 터미널 B에서 `Ctrl+C`로 Mock을 종료한다. 재부팅하면 모든 프로세스가 사라지므로 따로 정리할 것은 없다.
2. Swagger UI 컨테이너는 켜 둬도 된다. 끄려면 `npm run docs:down`을 실행한다.
3. 터미널 A에서 변경 파일을 확인하고 작업 브랜치에서 커밋·푸시한다.

```sh
git status
git diff --cached --name-only
```

문서 수정·설정은 [브랜치명](../conventions/branch-names.md)과 [커밋 메시지](../conventions/commit-messages.md) 규칙을 따르는 작업 브랜치에서 PR로 공유한다. PR에는 변경한 계약·정책과 실제 수행한 검증을 적는다. `build/`, `.gradle/`, `.env`, 로그, 실제 토큰·API 키·개인정보는 커밋하지 않는다.

## 7. 자주 발생하는 문제

| 증상 | 확인·해결 |
|---|---|
| `dockerDesktopLinuxEngine` 또는 named pipe를 찾을 수 없음 | Docker Desktop 실행 후 엔진 시작 대기. `docker info` 확인. Windows 컨테이너 모드라면 Linux 컨테이너로 전환 |
| macOS에서 `Cannot connect to the Docker daemon` | Docker Desktop 앱을 실행하고 엔진 시작 후 `docker info` 재확인 |
| `http://localhost:8081`이 열리지 않음 | `docker ps`에서 `allcheck-swagger-ui`가 실행 중인지 확인. 없으면 `npm run docs:up`. 8081 포트를 다른 프로그램이 쓰고 있지 않은지 확인 |
| Swagger UI에 변경이 보이지 않음 | 파일 저장 여부 확인 후 강력 새로고침 |
| `Try it out` 결과가 `Failed to fetch` | Servers에서 `http://127.0.0.1:4010`을 선택했는지, 터미널 B에서 Mock이 실행 중인지 확인. 기본 서버(`api.example.invalid`)는 존재하지 않는 주소 |
| Servers 목록에 `http://127.0.0.1:4010`이 없음 | 최신 코드를 받았는지 확인하고 브라우저 강력 새로고침 |
| `ENOENT … openapi.yaml` | 프로젝트 루트(`capstone`)가 아닌 곳에서 실행함. `cd`로 이동 후 다시 실행 |
| Mock 호출이 `401` | `Authorize`에 임의의 Bearer 값을 입력하거나 `Authorization: Bearer test` 헤더 추가. 로그인 계열 API는 인증이 필요 없음 |
| Mock 호출이 `404` | 경로에 `/api/v1`을 붙이지 않았는지, 명세의 경로·메서드와 일치하는지 확인. 브라우저 주소창으로 연 경우 `GET`만 되고 헤더가 없어 `401`이 정상 |
| Mock 호출이 `422` | 필수 헤더(`Idempotency-Key` 등)·파라미터·요청 본문이 명세와 맞는지 확인. 예시 값 그대로 보내면 대부분 통과 |
| Mock 시작 시 4010 포트가 이미 사용 중 | 기존 Mock 터미널을 `Ctrl+C`로 종료. 터미널을 닫았는데 남아 있다면 아래 명령으로 종료 |
| 명세를 고쳤는데 Mock 응답이 그대로 | Mock을 종료하고 `npm run mock` 다시 실행 |
| `EBADENGINE` 경고 | Node.js 버전이 24 미만. 동작은 하지만 24 이상으로 올린다 |

4010 포트를 쓰는 프로세스를 직접 종료해야 할 때는 다음을 사용한다. 같은 포트를 쓰는 프로세스만 종료한다.

```powershell
# Windows PowerShell
Get-Process -Id (Get-NetTCPConnection -LocalPort 4010).OwningProcess | Stop-Process
```

```bash
# macOS
kill $(lsof -ti tcp:4010)
```

## 부록: 백엔드와 DB 실행(구현 후)

컨트롤러를 구현한 뒤 실제 API를 호출하려면 Spring Boot 백엔드와 PostgreSQL을 실행한다. 백엔드는 시작할 때 DB 연결과 Flyway·JPA 초기화를 수행하므로 DB 컨테이너가 먼저 떠 있어야 한다. 지금은 컨트롤러가 없어 이 방법으로 `Try it out`을 해도 API 호출은 동작하지 않는다.

### 추가 준비물

JDK 21이 필요하다. Windows는 `winget install --id EclipseAdoptium.Temurin.21.JDK --exact`, macOS는 [Adoptium](https://adoptium.net/temurin/releases/?version=21)의 Java 21 `.pkg` 또는 `brew install --cask temurin@21`로 설치한다. 터미널을 새로 열고 `java -version`, `javac -version`이 모두 21인지 확인한다. 21이 아니면 `JAVA_HOME`이 JDK 21 폴더를 가리키는지 확인한다. macOS는 아래로 설정할 수 있다.

```bash
export JAVA_HOME="$(/usr/libexec/java_home -v 21)"
export PATH="$JAVA_HOME/bin:$PATH"
```

### 실행

프로젝트 루트에서 DB를 시작한다. `healthy`가 될 때까지 기다린다. 현재 DB는 `localhost:15432`, 데이터베이스 `allcheck`를 사용하며 백엔드의 `application-local.yml`과 같은 로컬 개발용 접속 설정이 들어 있다. 이 기본 비밀번호를 공용 서버·운영 환경에서 재사용하지 않는다.

```sh
docker compose -f infra/compose/compose.local.yml up -d
docker compose -f infra/compose/compose.local.yml ps
```

백엔드를 실행한다. 이 터미널은 서버가 종료될 때까지 열어 둔다.

```powershell
# Windows PowerShell
cd apps/backend
.\gradlew.bat bootRun --console=plain
```

```bash
# macOS
cd apps/backend
./gradlew bootRun --console=plain
```

`Started BackendApplication` 로그가 나오면 <http://localhost:8080/swagger-ui.html>을 연다. 이 Swagger UI는 3장의 Docker Swagger UI와 별개이며, 같은 `openapi.yaml`을 서버 빌드에 포함해 제공한다. macOS에서 `permission denied: ./gradlew`가 나오면 `apps/backend`에서 `chmod +x gradlew` 후 다시 실행한다.

기본 Spring Security 설정에서 로그인 화면이 나오면 사용자명 `user`와 서버 실행 로그의 `Using generated security password: ...` 뒤 값을 사용한다. 이 값은 서버를 재시작하면 바뀌며 GitHub에 올리지 않는다. 브라우저 로그인과 Swagger의 `Authorize`에서 요구하는 API Bearer 인증은 별개다.

### 명세 반영 경로

백엔드는 원본 명세를 빌드 시 복사해 제공한다. `build/resources`의 생성 파일을 직접 수정하지 않는다.

```text
docs/api/openapi.yaml
  → Gradle processResources
  → apps/backend/build/resources/main/static/openapi.yaml
  → http://localhost:8080/openapi.yaml
```

명세를 고친 뒤에는 `bootRun`을 `Ctrl+C`로 종료하고 다시 실행한다. 반영되지 않으면 `apps/backend`에서 두 파일의 해시가 같은지 확인한다.

```powershell
# Windows PowerShell
.\gradlew.bat processResources --console=plain
Get-FileHash ../../docs/api/openapi.yaml,build/resources/main/static/openapi.yaml
```

```bash
# macOS
./gradlew processResources --console=plain
shasum -a 256 ../../docs/api/openapi.yaml build/resources/main/static/openapi.yaml
```

### 백엔드 관련 문제

| 증상 | 확인·해결 |
|---|---|
| JDK `languageVersion=21`을 찾을 수 없음 | JDK 21 설치와 `JAVA_HOME` 확인. `apps/backend`에서 `gradlew --stop` 후 `gradlew -q javaToolchains` 실행 |
| DB 연결 거부·접속 실패 | Docker와 `postgres` 상태 확인. 로그는 `docker compose -f infra/compose/compose.local.yml logs --tail 100 postgres` |
| DB 비밀번호 인증 실패 | 기존 Docker 볼륨의 초기 비밀번호와 현재 설정이 다를 수 있음. 볼륨을 임의로 삭제하지 않고 DB 로그·기존 접속 정보 확인 |
| 8080 포트가 이미 사용 중 | 기존 `bootRun` 터미널에서 종료. 동시에 두 서버를 띄우지 않음 |
| Swagger가 비어 있거나 `/openapi.yaml`이 404 | 이 설정이 포함된 브랜치인지, `local` 프로파일인지 확인하고 위 해시 진단 실행 |

작업을 마치면 `bootRun` 터미널에서 `Ctrl+C`로 종료한다. DB를 중지하려면 아래를 실행한다. 데이터 볼륨은 유지된다.

```sh
docker compose -f infra/compose/compose.local.yml stop
```

이 가이드의 `localhost`는 접속하는 사람 자신의 PC를 뜻한다. 하나의 공용 주소에서 문서를 보거나 API를 테스트하려면 별도의 개발 서버 배포가 필요하다.
