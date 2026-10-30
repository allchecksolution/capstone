# Capstone AI Service

Python 3.13.9 기반의 독립 FastAPI 프로젝트입니다. ACK-16의 구현 범위는 서비스 초기 구성과 `GET /health`입니다.

## 구성

```text
apps/ai/
├── app/
│   ├── __init__.py
│   ├── main.py          # FastAPI 앱 및 health 엔드포인트
│   └── schemas.py       # Pydantic 응답 모델
├── tests/
│   └── test_health.py
├── .env.example
├── .gitignore
├── .python-version
└── pyproject.toml       # 실행·개발 의존성 및 pytest 설정
```

## Windows PowerShell 설치

저장소 루트에서 시작합니다. `python`이 Python 3.13.9를 가리키는지 확인합니다. 다른 버전이라면 설치된 3.13.9 실행 파일의 절대 경로로 가상환경을 생성합니다.

```powershell
cd apps/ai
python --version  # Python 3.13.9
python -m venv .venv
.\.venv\Scripts\python.exe --version
.\.venv\Scripts\python.exe -m pip install -e ".[dev]"
```

가상환경 Python을 직접 호출하므로 PowerShell 활성화 스크립트나 실행 정책 변경이 필요하지 않습니다. Python 버전은 `pyproject.toml`에서 3.13.9로 제한합니다. `.python-version`은 버전 관리 도구용 표시이며 Python을 자동 설치하지 않습니다.

실행 의존성은 FastAPI, Pydantic, Uvicorn이며 개발 의존성은 pytest와 TestClient용 HTTPX입니다. 의존성은 호환 버전 범위로 선언되어 있으며 전체 전이 의존성을 고정한 lockfile은 아직 없습니다.

## 환경변수

현재 필요한 환경변수는 없으며 `.env`도 자동 로딩하지 않습니다. `.env.example`은 비밀값 없는 안내 파일입니다. 향후 환경변수가 추가될 때만 아래와 같이 로컬 파일을 준비하고 별도 로딩 방식을 구현합니다.

```powershell
Copy-Item .env.example .env
```

`.env`와 `.env.*`는 루트 `.gitignore`가 제외합니다. 이 프로젝트는 `.env.example`만 예외로 허용합니다. 실제 API 키, 토큰, 고객 정보, 녹음 파일은 저장소에 넣지 않습니다.

## 개발 서버 실행

`apps/ai`에서 실행합니다.

```powershell
.\.venv\Scripts\python.exe -m uvicorn app.main:app --host 127.0.0.1 --port 8000 --reload
```

종료는 `Ctrl+C`입니다. 다른 PowerShell 창에서 확인합니다.

```powershell
$response = Invoke-WebRequest -Uri http://127.0.0.1:8000/health
$response.StatusCode  # 200
$response.Content     # {"status":"ok"}
```

`GET /health`는 Pydantic `HealthResponse` 모델을 통해 HTTP 200과 `{"status":"ok"}`를 반환합니다. 서비스 응답 여부만 확인하며 외부 서비스나 DB 연결은 검사하지 않습니다. OpenAPI 문서는 서버 실행 후 `http://127.0.0.1:8000/docs`에서 확인할 수 있습니다.

## 테스트

`apps/ai`에서 실행합니다. 별도로 서버를 켤 필요는 없습니다.

```powershell
.\.venv\Scripts\python.exe -m pytest
```

FastAPI TestClient로 `/health`의 HTTP 상태 코드와 JSON 응답을 검증합니다. 테스트 방식은 [FastAPI 공식 문서](https://fastapi.tiangolo.com/tutorial/testing/)를 따릅니다.

## 초기 검증 결과

2026-10-30 Windows / Python 3.13.9 환경에서 다음을 확인했습니다.

- `pip install -e ".[dev]"` 성공
- FastAPI 0.162.0, Pydantic 2.14.2, Uvicorn 0.54.0, pytest 9.2.0, HTTPX 0.28.1 설치
- `python -m pytest`: 1 passed, 1 warning
- `python -m pip check`: 의존성 충돌 없음
- Uvicorn을 실제 실행한 뒤 `GET http://127.0.0.1:8000/health`: HTTP 200, `{"status":"ok"}` 확인 후 종료

테스트 경고는 Starlette 1.10.0의 TestClient가 HTTPX 사용을 deprecated로 표시하고 `httpx2` 설치를 권장하는 내용입니다. 현재 테스트는 통과하며, 향후 TestClient 의존성 전환 시 재검증이 필요합니다. 위 버전은 이번 설치 결과이며 lockfile에 고정된 버전은 아닙니다.

## 범위와 후속 작업

학교 서버와 Tailscale을 통한 운영은 예정 사항입니다. 현재 명령은 로컬 개발용이며 서버 배포, Tailscale 설정, Docker Compose 및 공통 GitHub Actions 변경은 포함하지 않습니다. OpenAI GPT·CLOVA Speech 호출, 상담 분석 API, Kotlin Backend 연동도 구현하지 않았습니다.
