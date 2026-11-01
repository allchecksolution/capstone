# 팀원용 Swagger 로컬 실행 가이드

Windows 11의 PowerShell과 macOS의 터미널(zsh)에서 저장소를 받아 같은 환경으로 API 명세를 확인하고 수정하는 방법이다. 이 가이드의 변경 사항이 포함된 브랜치 또는 병합된 `main`을 받아야 한다. 공통 명령은 두 OS에서 동일하며, Gradle·환경 변수·해시 확인은 OS별 명령을 사용한다.

각 팀원의 PC에서 PostgreSQL과 Spring Boot를 실행한다. GitHub에 코드를 올리는 것만으로 공용 Swagger 서버가 만들어지지는 않는다. 현재 OpenAPI는 대표 API 10개의 설계 초안이며, API 컨트롤러는 아직 구현되지 않았다. Swagger에서 문서 확인은 가능하지만 실제 업무 API 호출은 구현 후 검증한다.

## 1. 준비할 프로그램

| 프로그램 | 용도 | 확인 명령 |
|---|---|---|
| Git | 저장소 받기·변경 공유 | `git --version` |
| JDK 21 | 백엔드 컴파일·실행 | `java -version`, `javac -version` |
| Docker Desktop | 로컬 PostgreSQL 실행 | `docker info`, `docker compose version` |
| Node.js 24 이상·npm | 팀 Git Hook 및 선택적인 명세 lint | `node --version`, `npm --version` |

Gradle과 PostgreSQL은 별도로 설치하지 않아도 된다. Gradle은 저장소의 Wrapper가 다운로드하고 PostgreSQL은 Docker 컨테이너로 실행한다. 최초 실행 시 패키지·이미지 다운로드를 위한 인터넷 연결이 필요하다.

### Windows 준비

JDK 21이 없다면 PowerShell에서 설치한다.

```powershell
winget install --id EclipseAdoptium.Temurin.21.JDK --exact
```

설치 후 터미널을 새로 열고 Java 버전을 확인한다. VS Code 터미널을 쓴다면 VS Code도 다시 시작한다. `JAVA_HOME`은 JDK 21 설치 폴더를 가리켜야 하며 `bin`까지 넣지 않는다. 설치 방법은 [Java Windows 안내](https://learn.microsoft.com/en-us/windows/dev-environment/java)를 참고한다.

[Docker Desktop 설치 안내](https://docs.docker.com/desktop/setup/install/windows-install/)에 따라 설치하고 앱을 실행한다. Linux 컨테이너 엔진이 시작된 뒤 `docker info`에 Server 정보가 출력되는지 확인한다. WSL 기반으로 실행할 때는 [WSL 2 설정 안내](https://docs.docker.com/desktop/features/wsl/)를 참고한다.

### macOS 준비

1. [Adoptium](https://adoptium.net/temurin/releases/?version=21)에서 **Java 21 / macOS / JDK / `.pkg`**를 선택해 설치한다. Apple Silicon(M 시리즈)은 `aarch64`, Intel Mac은 `x64`를 선택한다. 칩 종류는 Apple 메뉴의 **이 Mac에 관하여**에서 확인한다.
2. [Docker Desktop for Mac](https://docs.docker.com/desktop/setup/install/mac-install/)에서 같은 칩 종류의 설치 파일을 받아 설치하고 앱을 실행한다. macOS에는 WSL 설정이 필요하지 않다. `docker info`에 Server 정보가 나올 때까지 기다린다.
3. Git과 Node.js 24 이상을 준비한다. `git --version` 실행 시 macOS 개발 도구 설치 안내가 나오면 완료한다. Node.js는 [공식 다운로드](https://nodejs.org/en/download)에서 요구 버전을 설치한다.

JDK 설치 후 터미널에서 다음을 실행한다.

```bash
/usr/libexec/java_home -V
export JAVA_HOME="$(/usr/libexec/java_home -v 21)"
export PATH="$JAVA_HOME/bin:$PATH"
java -version
javac -version
```

`java`와 `javac`가 모두 21인지 확인한다. 매번 적용하려면 위 두 `export` 줄을 개인 `~/.zshrc`에 추가하고 터미널을 다시 연다. 기존 Java 설정이 있다면 해당 줄을 수정해 중복 설정을 피한다. bash를 쓰는 경우 해당 셸의 시작 설정 파일에 넣는다. 개인 셸 설정과 JDK 경로는 저장소에 추가하지 않는다. `java_home`을 사용하는 설정은 [JDK macOS 설치 문서](https://docs.oracle.com/en/java/javase/21/install/installation-jdk-macos.html)를 참고한다.

## 2. 저장소 받기

저장소 접근 권한을 받은 뒤 원하는 상위 폴더에서 실행한다.

```sh
git clone https://github.com/allchecksolution/capstone.git
cd capstone
npm ci
```

이미 복제한 팀원은 다시 clone하지 않고 기존 저장소를 사용한다. 작업 중인 변경을 먼저 확인하고, 해당 설정이 포함된 브랜치의 최신 변경을 받는다. 아직 `main`에 병합되지 않았다면 공유받은 브랜치를 체크아웃해야 한다.

`npm ci`는 팀의 커밋·브랜치 검사 Hook을 설치한다. 상세 규칙은 [팀 협업 가이드](../../team-guide.md)를 따른다.

이후 **프로젝트 루트**는 아래 파일들이 있는 `capstone` 폴더를 뜻한다. 개인별 드라이브·상위 폴더 이름은 달라도 된다.

```text
capstone/
├─ apps/backend/
├─ docs/api/openapi.yaml
├─ infra/compose/compose.local.yml
└─ package.json
```

## 3. 로컬 DB 실행

Docker Desktop을 켠 상태에서 프로젝트 루트에서 실행한다.

```sh
docker compose -f infra/compose/compose.local.yml up -d
docker compose -f infra/compose/compose.local.yml ps
```

`postgres` 서비스가 실행되고 상태가 `healthy`가 되면 다음 단계로 진행한다. 처음에는 `health: starting`으로 보일 수 있다. 계속 실패하면 같은 위치에서 로그를 확인한다.

```sh
docker compose -f infra/compose/compose.local.yml logs --tail 100 postgres
```

현재 DB는 호스트의 `localhost:15432`, 데이터베이스 `allcheck`를 사용한다. 백엔드의 `application-local.yml`과 Compose에 같은 로컬 개발용 접속 설정이 들어 있으므로 최초 실행에 별도 `.env` 작성은 필요하지 않다. 이 기본 비밀번호를 공용 서버·운영 환경에서 재사용하지 않는다.

Docker를 실행하는 이유는 백엔드가 시작할 때 DB 연결과 Flyway·JPA 초기화를 수행하기 때문이다. PostgreSQL 컨테이너가 Swagger UI를 제공하는 것은 아니다.

## 4. 백엔드 실행과 Swagger 접속

프로젝트 루트에서 OS에 맞는 명령을 실행한다.

**Windows PowerShell**

```powershell
cd apps/backend
.\gradlew.bat bootRun --console=plain
```

**macOS 터미널**

```bash
cd apps/backend
./gradlew bootRun --console=plain
```

`gradlew`는 저장소에서 실행 권한이 설정되어 있다. macOS에서 `permission denied: ./gradlew`가 나오면 `apps/backend`에서 `chmod +x gradlew` 후 다시 실행한다. Windows용 `gradlew.bat`은 macOS에서 사용하지 않는다.

기본 프로파일은 `local`이다. `Started BackendApplication` 로그와 8080 포트의 서버 시작 로그가 출력되면 브라우저에서 [Swagger UI](http://localhost:8080/swagger-ui.html)를 연다.

`bootRun`은 서버가 종료될 때까지 계속 실행된다. Gradle 진행률이 85% 또는 다른 값에서 머무르는 것만으로 오류라고 판단하지 않는다. 터미널을 열어 둔 채 브라우저를 사용한다.

현재 기본 Spring Security 설정에서 로그인 화면이 나타나면 다음 값을 사용한다.

| 항목 | 값 |
|---|---|
| 사용자명 | `user` |
| 비밀번호 | 현재 서버 실행 로그의 `Using generated security password: ...` 뒤 값 |

생성된 비밀번호는 서버 재시작 시 바뀔 수 있으며 각 팀원이 자신의 실행 로그에서 확인한다. 이 비밀번호를 GitHub에 올리지 않는다. 브라우저 로그인과 Swagger의 `Authorize` 버튼에서 요구하는 API Bearer 인증은 별개다.

Swagger에서 `올체크 업무 흐름 대표 API 초안`과 API 목록이 표시되면 문서 연결이 완료된 것이다. 이 가이드가 저장소에 포함된 이후에는 Swagger 설정을 추가하거나 YAML을 수동으로 복사할 필요가 없다.

## 5. 명세 수정과 반영

수정할 파일은 **`docs/api/openapi.yaml` 원본 하나**다. 화면과 업무 규칙이 바뀌면 [화면별 대응표](screen-api-mapping.md)와 [업무 규칙](business-rules.md)도 함께 검토한다.

자동 포함 경로는 다음과 같다.

```text
docs/api/openapi.yaml
  → Gradle processResources
  → apps/backend/build/resources/main/static/openapi.yaml
  → http://localhost:8080/openapi.yaml
```

`application-local.yml`의 다음 설정에서 `/openapi.yaml`은 소스 파일 경로가 아니라 서버가 제공하는 URL이다. Spring Boot는 `static` 내부 파일을 URL 루트 아래에서 제공한다.

```yaml
springdoc:
  swagger-ui:
    url: /openapi.yaml
```

수정 후 실행 중인 터미널에서 `Ctrl+C`로 서버를 종료하고 `apps/backend`에서 다시 실행한다.

**Windows PowerShell**

```powershell
.\gradlew.bat bootRun --console=plain
```

**macOS 터미널**

```bash
./gradlew bootRun --console=plain
```

브라우저를 새로고침해 변경 사항을 확인한다. 저장만으로 실행 중인 서버에 즉시 반영되는 구성은 아니다. `build/resources`의 생성 파일을 직접 수정하거나 `src/main/resources/static/openapi.yaml` 복사본을 만들지 않는다. Gradle 빌드 시 배포 JAR에도 동일한 원본 명세가 포함된다.

명세 구조를 검사하려면 프로젝트 루트의 별도 터미널에서 실행한다. 첫 실행에는 CLI 패키지 다운로드가 필요하다.

```sh
npx @redocly/cli@latest lint docs/api/openapi.yaml
```

명세 lint 통과는 실제 백엔드의 동작·권한·동시성 테스트 통과를 의미하지 않는다. [Redocly CLI 안내](https://redocly.com/docs/cli/installation)를 참고한다.

## 6. 자주 발생하는 문제

| 증상 | 확인·해결 |
|---|---|
| `dockerDesktopLinuxEngine` 또는 named pipe를 찾을 수 없음 | Docker Desktop 실행 후 엔진 시작 대기. `docker info` 확인. Windows 컨테이너 모드라면 Linux 컨테이너로 전환 |
| macOS에서 `Cannot connect to the Docker daemon` | Docker Desktop 앱을 실행하고 엔진 시작 후 `docker info` 재확인 |
| macOS에서 `permission denied: ./gradlew` | `apps/backend`에서 `chmod +x gradlew` 후 다시 실행 |
| macOS에서 `java_home`이 JDK 21을 찾지 못함 | 칩 종류에 맞는 JDK 21 `.pkg` 설치 후 `/usr/libexec/java_home -V` 확인 |
| JDK `languageVersion=21`을 찾을 수 없음 | JDK 21 설치 후 `JAVA_HOME`·`Path` 확인. 아래 Java 진단 명령 실행 |
| DB 연결 거부·접속 실패 | Docker와 `postgres` 상태 확인. `localhost:15432` 접속 설정이 Compose와 일치하는지 확인 |
| DB 비밀번호 인증 실패 | 기존 Docker 볼륨의 초기 비밀번호와 현재 설정이 다를 수 있음. DB 로그·기존 접속 정보 확인. 볼륨을 임의로 삭제하지 않음 |
| 8080 포트가 이미 사용 중 | 기존 `bootRun` 터미널에서 종료. 동시에 두 서버를 띄우지 않음 |
| Swagger 요청에 401 또는 로그인 화면 | 브라우저에서 접속하고 현재 실행의 기본 사용자·비밀번호로 로그인 |
| Swagger가 비어 있거나 `/openapi.yaml`이 404 | 이 설정이 포함된 브랜치인지, `local` 프로파일인지, `springdoc`이 `spring` 바깥인지 확인. 아래 리소스 진단 명령 실행 |
| YAML 수정이 화면에 반영되지 않음 | 원본을 수정했는지 확인하고 `bootRun` 재실행 후 브라우저 강력 새로고침 |
| `Try it out` 호출 실패 | 현재 명세의 `api.example.invalid`는 예시 주소이며 API도 아직 미구현. 실제 호출은 구현 후 접근 가능한 API 주소·인증·필요 시 CORS 설정 후 진행 |

Java 진단 명령은 `apps/backend`에서 실행한다.

**Windows PowerShell**

```powershell
java -version
javac -version
$env:JAVA_HOME
.\gradlew.bat --stop
.\gradlew.bat -q javaToolchains
```

**macOS 터미널**

```bash
java -version
javac -version
echo "$JAVA_HOME"
/usr/libexec/java_home -V
./gradlew --stop
./gradlew -q javaToolchains
```

JDK 21을 설치했지만 `JAVA_HOME`이 17을 가리키면 Windows에서는 환경 변수에서 21 설치 폴더로 변경하고, macOS에서는 준비 단계의 `export` 설정을 적용한다. 터미널·IDE를 다시 열고 확인한다. IDE에서 직접 실행할 때도 프로젝트 SDK와 Gradle JVM을 JDK 21로 맞춘다.

리소스 진단도 `apps/backend`에서 실행한다. 두 해시가 같으면 원본이 빌드 리소스에 포함된 것이다.

**Windows PowerShell**

```powershell
.\gradlew.bat processResources --console=plain
Get-FileHash ../../docs/api/openapi.yaml,build/resources/main/static/openapi.yaml
```

**macOS 터미널**

```bash
./gradlew processResources --console=plain
shasum -a 256 ../../docs/api/openapi.yaml build/resources/main/static/openapi.yaml
```

## 7. 작업 종료와 GitHub 공유

서버 터미널에서 `Ctrl+C`로 백엔드를 종료한다. DB도 중지하려면 프로젝트 루트의 터미널에서 실행한다. 데이터 볼륨은 유지된다.

```sh
docker compose -f infra/compose/compose.local.yml stop
```

문서 수정·설정은 [브랜치명](../conventions/branch-names.md)과 [커밋 메시지](../conventions/commit-messages.md) 규칙을 따르는 작업 브랜치에서 PR로 공유한다. Jira에서 `main` 기준 브랜치를 만드는 팀 절차를 따른다. PR에는 변경한 계약·정책과 실제 수행한 검증을 적는다.

커밋에는 원본 명세·관련 문서·필요한 Gradle 및 Spring 설정을 포함한다. `build/`, `.gradle/`, `.env`, 로그, 실제 토큰·API 키·개인정보·개인 PC의 JDK 절대 경로는 포함하지 않는다. 현재 `build/`, `.gradle/`, `.env`와 백엔드 로그는 ignore 대상이다. 스테이징 후 루트에서 확인한다.

```sh
git diff --cached --name-only
git diff --cached
```

이 가이드의 `localhost`는 접속하는 사람 자신의 PC를 뜻한다. 팀원은 각자 서버를 실행한다. 하나의 공용 주소에서 문서를 보거나 API를 테스트하려면 별도의 개발 서버 배포가 필요하다.
