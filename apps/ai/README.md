# Capstone AI Service

Python 3.13.9 기반 FastAPI 서비스입니다. `GET /health`는 HTTP 200과 `{"status":"ok"}`를 반환합니다. 외부 AI·DB 연결을 검사하지 않는 서비스 상태 확인 API입니다.

## 구현 전 내부 API 계약

[AI OpenAPI](docs/openapi.yaml)는 동기 STT·구조화 요청을 정의하고, [Backend OpenAPI](../../docs/api/openapi.yaml)는 앱 업로드·진행 조회·푸시 계약을 정의합니다.
실행 순서·상태·재분석 정책은 [BR-09](../backend/docs/business-specification.md#br-09)를 따릅니다. 실제 실행 API는 현재 /health입니다.

[계약 검증 스크립트](../../scripts/validate-api-contracts.py)는 두 명세의 구조·참조·예시·공유 스키마와 정상/실패 회귀 사례를 검사합니다. S3·AI·FCM을 호출하거나 DB를 변경하지 않습니다.
검증 도구는 앱 의존성과 분리된 환경에 설치하고 저장소 루트에서 실행합니다.

~~~sh
python3 -m venv /tmp/capstone-api-contract-venv
/tmp/capstone-api-contract-venv/bin/python -m pip install PyYAML==6.0.3 openapi-spec-validator==0.7.2 jsonschema==4.25.1
/tmp/capstone-api-contract-venv/bin/python scripts/validate-api-contracts.py
~~~

## 구성과 버전 정책

- `app/main.py`: FastAPI 앱 및 health 엔드포인트
- `app/schemas.py`: Pydantic 응답 모델
- `tests/test_health.py`: 기존 TestClient 테스트
- `pyproject.toml`: 실행 의존성과 개발용 `dev` 그룹
- `uv.lock`: Windows·Linux를 고려해 잠근 직접·간접 의존성 및 배포 파일 해시
- `Dockerfile`: `test`, `production-build`, `runtime` 단계
- `.dockerignore`: 가상환경·도구·실제 환경파일·캐시를 빌드에서 제외

Python은 기존 정책인 3.13.9를 유지하며 uv는 0.13.7로 고정합니다. FastAPI·Pydantic·Uvicorn은 실행 의존성, pytest·HTTPX2는 개발 의존성입니다. 선언된 버전 범위에서 `uv.lock`을 생성하며, 공통 실행에서는 `--locked`를 사용해 잠금 파일이 달라져야 할 때 실패하도록 합니다. 의존성을 변경할 때는 별도 작업에서 `uv lock` 후 Windows와 Linux 테스트를 실행합니다. 빌드 도구 setuptools는 기존 버전 범위를 사용하며 격리 빌드 의존성은 프로젝트 lock의 고정 대상이 아닙니다.

## Windows PowerShell 로컬 개발

저장소 루트에서 시작합니다. `python --version`이 정확히 3.13.9인지 확인합니다. uv 설치용 `.tools`와 앱용 `.venv`는 별도이며 Git·Docker에서 제외됩니다. 활성화 스크립트나 실행 정책 변경은 필요하지 않습니다.

```powershell
cd apps/ai
python --version
python -m venv .tools
.\.tools\Scripts\python.exe -m pip install uv==0.13.7
$aiUv = Join-Path (Get-Location) '.tools/Scripts/uv.exe'
& $aiUv --version
& $aiUv sync --locked
& $aiUv run --locked pytest -W error::DeprecationWarning -p no:cacheprovider
& $aiUv pip check
& $aiUv lock --check
& $aiUv run --locked uvicorn app.main:app --host 127.0.0.1 --port 8000 --reload
```

`Ctrl+C`로 서버를 종료합니다. 별도 PowerShell 창에서 확인합니다.

```powershell
$response = Invoke-WebRequest http://127.0.0.1:8000/health
$response.StatusCode  # 200
$response.Content     # {"status":"ok"}
```

OpenAPI 문서는 `http://127.0.0.1:8000/docs`에서 확인할 수 있습니다. 테스트에는 별도의 서버 실행이 필요하지 않습니다. 이전의 `pip install -e ".[dev]"` 대신 uv 명령을 사용합니다.

uv에서 캐시 임시 파일 이동 오류(`os error 17`)가 발생하면 현재 PowerShell 프로세스에서만 다음을 설정한 뒤 재시도합니다. 시스템 환경변수는 바뀌지 않습니다.

```powershell
$env:UV_CACHE_DIR = Join-Path (Get-Location) '.tools/cache'
$env:TEMP = Join-Path (Get-Location) '.tools/tmp'
$env:TMP = $env:TEMP
New-Item -ItemType Directory -Force -Path $env:TEMP | Out-Null
& $aiUv sync --locked
```

## Docker 공통 실행 방법

Docker Desktop의 Linux 엔진이 실행 중이어야 합니다. 모든 명령은 `apps/ai`에서 실행합니다. 호스트에 uv나 Python을 설치하지 않아도 Docker 빌드·테스트·실행은 가능합니다.

```powershell
cd apps/ai  # 이미 apps/ai라면 생략
# Linux 엔진 연결 확인
docker version

# 개발 의존성을 포함한 테스트 이미지
docker build --target test -t capstone-ai:ack18-test .
docker run --rm capstone-ai:ack18-test

# 개발 의존성이 없는 운영 이미지 (기본 최종 단계도 runtime)
docker build --target runtime -t capstone-ai:ack18 .
docker run --detach --rm --name capstone-ai-ack18 -p 127.0.0.1:8000:8000 capstone-ai:ack18
```

호스트 8000 포트를 사용 중이면 기존 서비스를 종료하지 말고 `-p 127.0.0.1:18000:8000`처럼 다른 포트로 실행합니다. 이 경우 확인 URL도 18000으로 바꿉니다.

```powershell
$response = Invoke-WebRequest http://127.0.0.1:8000/health
$response.StatusCode  # 200
$response.Content     # {"status":"ok"}
docker inspect --format '{{.State.Health.Status}}' capstone-ai-ack18
docker logs capstone-ai-ack18
docker stop capstone-ai-ack18
```

운영 컨테이너는 UID/GID 10001의 비루트 사용자로 실행합니다. 운영 단계는 `uv sync --locked --no-dev --no-editable`로 만든 환경만 복사하며 테스트 코드·pytest·HTTPX2·uv 도구를 포함하지 않습니다. 컨테이너 내부 Uvicorn은 `0.0.0.0:8000`, 호스트 공개 범위는 localhost입니다. Docker HEALTHCHECK도 동일한 `/health` 응답을 확인합니다.

운영 의존성 분리는 다음 명령으로 확인할 수 있습니다.

```powershell
docker run --rm capstone-ai:ack18 python -c "import importlib.util, os; assert os.getuid() == 10001; assert all(importlib.util.find_spec(n) is None for n in ('pytest', 'httpx', 'httpx2')); import app.main; print('runtime dependencies: OK')"
```

Python 이미지는 `python:3.13.9-slim-bookworm`, uv 이미지는 `ghcr.io/astral-sh/uv:0.13.7`을 사용합니다. 태그는 고정했지만 digest와 OS 패키지까지 완전히 고정한 빌드는 아닙니다. Python 패치·기본 이미지 보안 업데이트는 별도로 검토해야 합니다.

## ACK-18 검증 결과

2026-11-05 Python 3.13.9 / uv 0.13.7로 다음을 확인했습니다.

- Windows: `uv sync --locked`, `uv lock --check`, `uv pip check` 성공
- Windows: deprecation 경고를 오류로 취급한 기존 pytest 1개 통과, 경고 없음
- Docker Linux/amd64: 테스트 이미지 빌드 및 컨테이너 pytest 1개 통과, 경고 없음
- 운영 이미지 빌드 성공, UID 10001 실행 확인
- 운영 이미지에 pytest·HTTPX·HTTPX2·httpcore2·pygments·pluggy·iniconfig 및 uv 실행 파일이 없음을 확인
- 운영 컨테이너 실제 `/health`: HTTP 200, `{"status":"ok"}`
- Docker HEALTHCHECK: `healthy`, 검증용 컨테이너 종료 완료

주요 잠금 버전은 FastAPI 0.162.0, Starlette 1.10.0, Pydantic 2.14.2, Uvicorn 0.54.0, pytest 9.2.0, HTTPX2 2.16.0입니다. 다른 CPU 아키텍처·학교 서버·Tailscale 배포는 검증하지 않았습니다.

## 환경변수 및 범위

현재 필요한 환경변수는 없으며 `.env` 자동 로딩도 하지 않습니다. `.env.example`은 비밀값 없는 안내 파일입니다. API 키·토큰·고객 정보·녹음 파일은 저장소에 넣지 않습니다. 실제 `.env`와 `.env.*`는 Git 및 Docker에서 제외하고 `.env.example`만 예외로 둡니다.

기존 `/health` API·Pydantic 모델·테스트를 유지합니다. Starlette TestClient가 권장하는 HTTPX2로 개발 의존성을 전환하며 deprecation 경고를 오류로 취급해 호환성을 검증합니다. 기존 infra/compose, PostgreSQL, Spring Boot, Flutter, 루트 npm 및 공통 CI 설정은 변경하지 않습니다. 외부 AI 호출, 상담 분석, 학교 서버 배포·Tailscale 설정은 이번 범위에 포함하지 않습니다.
