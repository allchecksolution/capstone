from uuid import uuid4

from fastapi import FastAPI, Request
from fastapi.exception_handlers import http_exception_handler
from fastapi.exceptions import RequestValidationError
from fastapi.responses import JSONResponse
from starlette.exceptions import HTTPException
from starlette.responses import Response

from app.analysis_schemas import AnalyzeRequest, AnalyzeResponse, ErrorResponse
from app.consultation import InvalidModelOutput, ProviderUnavailable, analyze_consultation
from app.schemas import HealthResponse

app = FastAPI(title="Capstone AI Service")


@app.get("/health", response_model=HealthResponse)
async def health() -> HealthResponse:
    return HealthResponse(status="ok")


def error_response(status_code: int, code: str, message: str) -> JSONResponse:
    error = ErrorResponse(code=code, message=message, traceId=uuid4().hex)
    return JSONResponse(status_code=status_code, content=error)


@app.exception_handler(RequestValidationError)
async def invalid_request(_request: Request, _error: RequestValidationError) -> JSONResponse:
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
    responses={
        422: {"model": ErrorResponse, "description": "입력값 오류"},
        500: {"model": ErrorResponse, "description": "모델 응답 검증 실패"},
        503: {"model": ErrorResponse, "description": "외부 분석 서비스 호출 실패"},
    },
)
def analyze(request: AnalyzeRequest) -> AnalyzeResponse | JSONResponse:
    # TODO(ACK-24): 내부 인증, 시간대 불일치, 409 발생 조건은 팀 합의 후 적용한다.
    try:
        return analyze_consultation(request)
    except ProviderUnavailable:
        return error_response(503, "TEMPORARILY_UNAVAILABLE", "분석 서비스를 사용할 수 없습니다.")
    except InvalidModelOutput:
        return error_response(500, "INTERNAL_ERROR", "분석 결과를 검증할 수 없습니다.")
