from hmac import compare_digest
from os import getenv
from uuid import uuid4

from fastapi import Depends, FastAPI, Request
from fastapi.security import APIKeyHeader
from fastapi.exception_handlers import http_exception_handler
from fastapi.exceptions import RequestValidationError
from fastapi.responses import JSONResponse
from starlette.exceptions import HTTPException
from starlette.responses import Response

from app.analysis_schemas import AnalyzeRequest, AnalyzeResponse, ErrorResponse
from app.consultation import (
    InvalidModelOutput,
    ProviderFailed,
    ProviderTimeout,
    ProviderUnavailable,
    analyze_consultation,
)
from app.schemas import HealthResponse

app = FastAPI(title="Capstone AI Service")


@app.get("/health", response_model=HealthResponse)
async def health() -> HealthResponse:
    return HealthResponse(status="ok")


def error_response(status_code: int, code: str, message: str) -> JSONResponse:
    error = ErrorResponse(code=code, message=message, traceId=uuid4().hex)
    return JSONResponse(status_code=status_code, content=error)


@app.middleware("http")
async def authenticate_analysis(request: Request, call_next):
    if request.method == "POST" and request.url.path.rstrip("/") == "/internal/v1/analyze":
        configured_key = getenv("AI_INTERNAL_API_KEY")
        if not configured_key:
            return error_response(503, "TEMPORARILY_UNAVAILABLE", "분석 서비스를 사용할 수 없습니다.")
        keys = request.headers.getlist("X-Internal-Api-Key")
        if len(keys) == 1 and compare_digest(keys[0].encode(), configured_key.encode()):
            return await call_next(request)
        restricted_key = getenv("AI_INTERNAL_NO_ANALYZE_API_KEY")
        if restricted_key and len(keys) == 1 and compare_digest(keys[0].encode(), restricted_key.encode()):
            return error_response(403, "FORBIDDEN", "인증된 호출 주체의 권한이 부족합니다.")
        return error_response(401, "UNAUTHENTICATED", "내부 API Key가 없거나 올바르지 않습니다.")
    return await call_next(request)


@app.exception_handler(RequestValidationError)
async def invalid_request(_request: Request, _error: RequestValidationError) -> JSONResponse:
    if any(error["type"] == "time_zone_offset_mismatch" for error in _error.errors()):
        return error_response(422, "TIME_ZONE_OFFSET_MISMATCH", "기준 시각 오프셋이 업무 시간대와 일치하지 않습니다.")
    return error_response(422, "VALIDATION_FAILED", "입력값 오류")


@app.exception_handler(HTTPException)
async def invalid_http_request(request: Request, error: HTTPException) -> Response:
    # JSON 본문 디코딩 실패 중 일부는 FastAPI에서 400으로 전달된다.
    if error.status_code == 400 and request.url.path == "/internal/v1/analyze":
        return error_response(422, "VALIDATION_FAILED", "입력값 오류")
    return await http_exception_handler(request, error)


@app.post(
    "/internal/v1/analyze",
    response_model=AnalyzeResponse,
    dependencies=[Depends(APIKeyHeader(name="X-Internal-Api-Key", scheme_name="internalApiKey", auto_error=False))],
    responses={
        401: {"model": ErrorResponse, "description": "인증 실패"},
        403: {"model": ErrorResponse, "description": "권한 부족"},
        502: {"model": ErrorResponse, "description": "공급자 처리 실패"},
        504: {"model": ErrorResponse, "description": "분석 타임아웃"},
        422: {"model": ErrorResponse, "description": "입력값 오류"},
        500: {"model": ErrorResponse, "description": "모델 응답 검증 실패"},
        503: {"model": ErrorResponse, "description": "외부 분석 서비스 호출 실패"},
    },
)
def analyze(request: AnalyzeRequest) -> AnalyzeResponse | JSONResponse:
    try:
        return analyze_consultation(request)
    except ProviderTimeout:
        return error_response(504, "AI_TIMEOUT", "분석 제한 시간을 초과했습니다.")
    except ProviderFailed:
        return error_response(502, "AI_PROVIDER_FAILED", "분석 공급자 처리에 실패했습니다.")
    except ProviderUnavailable:
        return error_response(503, "TEMPORARILY_UNAVAILABLE", "분석 서비스를 사용할 수 없습니다.")
    except InvalidModelOutput:
        return error_response(500, "INTERNAL_ERROR", "분석 결과를 검증할 수 없습니다.")
