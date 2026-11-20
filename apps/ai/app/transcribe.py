"""Synchronous ACK-29 transcription endpoint."""

from base64 import b64decode, b64encode
from contextlib import closing
from datetime import datetime, timedelta, timezone
from hashlib import sha256
from hmac import compare_digest
from http.client import HTTPException, HTTPSConnection
import json
import logging
from math import isfinite
from os import getenv
from pathlib import Path
import re
import subprocess
from tempfile import TemporaryDirectory
from time import monotonic
from typing import Annotated, Literal
from urllib.parse import parse_qs, urlsplit
from uuid import uuid4

from fastapi import APIRouter, Depends, Request
from fastapi.exceptions import RequestValidationError
from fastapi.responses import JSONResponse
from fastapi.routing import APIRoute
from fastapi.security import APIKeyHeader
from pydantic import BaseModel, ConfigDict, Field, StringConstraints, field_validator
from starlette.exceptions import HTTPException as RequestHTTPException

WAV_TYPES = {"audio/wav", "audio/x-wav", "audio/wave", "audio/vnd.wave"}
S3_HOST = re.compile(r"[a-z0-9][a-z0-9-]*\.s3(?:\.[a-z0-9-]+)?\.amazonaws\.com")
S3_PATH_HOST = re.compile(r"s3(?:\.[a-z0-9-]+)?\.amazonaws\.com")
S3_BUCKET = re.compile(r"[a-z0-9][a-z0-9.-]{1,61}[a-z0-9]")
S3_KEY = re.compile(r"/recordings/[1-9][0-9]*/[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}")
CLOVA_HOST = "clovaspeech-gw.ncloud.com"
CLOVA_URL = re.compile(r"https://clovaspeech-gw\.ncloud\.com(/external/v1/[0-9]+/[A-Za-z0-9_-]+)/?")
RFC3339 = re.compile(
    r"\d{4}-\d{2}-\d{2}[Tt]\d{2}:\d{2}:\d{2}(?:\.\d+)?(?:[Zz]|[+-](?:[01]\d|2[0-3]):[0-5]\d)",
    re.ASCII,
)
CHUNK = 64 * 1024
MAX_TRANSCRIPT = 100_000
CLOVA_DIAGNOSTIC_LIMIT = 4096
CLOVA_ERROR_CODES = {
    "400",
    "401",
    "ERROR_AUDIO_CONVERSION",
    "ERROR_AUDIO_EMPTY",
    "ERROR_INVALID_SECRET",
    "ERROR_PARAMS_FORMAT_INVALID",
    "ERROR_REQUEST_PARAMETER",
    "STT002",
    "STT003",
    "STT004",
    "STT005",
}
log = logging.getLogger(__name__)
Id = Annotated[str, StringConstraints(min_length=1, max_length=64)]

ERRORS = {
    "VALIDATION_FAILED": (422, "입력값 오류"),
    "UNAUTHENTICATED": (401, "내부 API Key가 없거나 올바르지 않습니다."),
    "FORBIDDEN": (403, "인증된 호출 주체의 권한이 부족합니다."),
    "TEMPORARILY_UNAVAILABLE": (503, "전사 서비스를 사용할 수 없습니다."),
    "RECORDING_ACCESS_FAILED": (502, "녹음 원본을 읽을 수 없습니다."),
    "RECORDING_CHECKSUM_MISMATCH": (422, "녹음 체크섬이 일치하지 않습니다."),
    "RECORDING_TOO_LARGE": (413, "녹음 제한을 초과했습니다."),
    "UNSUPPORTED_MEDIA_TYPE": (415, "지원하지 않는 녹음 형식입니다."),
    "AI_PROVIDER_FAILED": (502, "전사 공급자 처리에 실패했습니다."),
    "AI_TIMEOUT": (504, "전사 제한 시간을 초과했습니다."),
    "INTERNAL_ERROR": (500, "전사 처리 중 오류가 발생했습니다."),
}


class StrictModel(BaseModel):
    model_config = ConfigDict(extra="forbid", strict=True)


class FileChecksum(StrictModel):
    algorithm: Literal["SHA256"]
    value: Annotated[str, StringConstraints(min_length=44, max_length=44)]

    @field_validator("value")
    @classmethod
    def valid_digest(cls, value: str) -> str:
        digest = b64decode(value, validate=True)
        if len(digest) != 32 or b64encode(digest).decode("ascii") != value:
            raise ValueError("Expected a Base64-encoded SHA256 digest.")
        return value


class RecordingSource(StrictModel):
    downloadUrl: Annotated[str, Field(pattern=r"^https://")]
    expiresAt: Annotated[str, Field(json_schema_extra={"format": "date-time"})]
    contentType: Annotated[str, StringConstraints(min_length=1, max_length=100)]
    sizeBytes: Annotated[int, Field(ge=1)]
    checksum: FileChecksum

    @field_validator("expiresAt")
    @classmethod
    def valid_expiry(cls, value: str) -> str:
        if not RFC3339.fullmatch(value):
            raise ValueError("Expected an RFC 3339 timestamp with an offset.")
        datetime.fromisoformat(value.upper())
        return value


class Identity(StrictModel):
    analysisRequestId: Id
    receptionId: Id
    receptionVersion: Annotated[int, Field(ge=1)]
    recordingFileId: Id


class TranscribeRequest(Identity):
    recording: RecordingSource


class TranscribeResponse(Identity):
    status: Literal["SUCCEEDED"]
    transcript: Annotated[str, StringConstraints(max_length=MAX_TRANSCRIPT)]


class ErrorResponse(StrictModel):
    code: str
    message: str
    traceId: str


class TranscribeError(Exception):
    pass


def error(code: str) -> JSONResponse:
    status, message = ERRORS[code]
    response = ErrorResponse(code=code, message=message, traceId=uuid4().hex)
    return JSONResponse(response.model_dump(), status_code=status)


class TranscribeRoute(APIRoute):
    def get_route_handler(self):
        handler = super().get_route_handler()

        async def authenticate(request: Request):
            configured_key = getenv("AI_INTERNAL_API_KEY")
            keys = request.headers.getlist("X-Internal-Api-Key")
            if not configured_key:
                return error("TEMPORARILY_UNAVAILABLE")
            if len(keys) != 1:
                return error("UNAUTHENTICATED")
            supplied = keys[0].encode()
            if not compare_digest(supplied, configured_key.encode()):
                restricted_key = getenv("AI_INTERNAL_NO_TRANSCRIBE_API_KEY")
                if restricted_key and compare_digest(supplied, restricted_key.encode()):
                    return error("FORBIDDEN")
                return error("UNAUTHENTICATED")
            try:
                return await handler(request)
            except RequestValidationError:
                return error("VALIDATION_FAILED")
            except RequestHTTPException as failure:
                if failure.status_code == 400:
                    return error("VALIDATION_FAILED")
                raise
            except TranscribeError as failure:
                return error(str(failure))
            except Exception:
                # Framework exception logging can expose request or provider data.
                return error("INTERNAL_ERROR")

        return authenticate


router = APIRouter(route_class=TranscribeRoute)


def positive_setting(name: str, ceiling: float = float("inf")) -> float:
    try:
        value = float(getenv(name, ""))
    except ValueError:
        raise TranscribeError("TEMPORARILY_UNAVAILABLE") from None
    if not isfinite(value) or not 0 < value <= ceiling:
        raise TranscribeError("TEMPORARILY_UNAVAILABLE")
    return value


def recording_target(source: RecordingSource, allowed_host: str, path_bucket: str) -> str:
    invalid = TranscribeError("VALIDATION_FAILED")
    url = source.downloadUrl
    if any(not 33 <= ord(c) <= 126 or c in "\\#" for c in url):
        raise invalid
    try:
        parts = urlsplit(url)
        object_path = parts.path
        if path_bucket:
            prefix = f"/{path_bucket}/"
            if not object_path.startswith(prefix):
                raise invalid
            object_path = object_path[len(prefix) - 1:]
        if parts.scheme != "https" or parts.netloc != allowed_host or not S3_KEY.fullmatch(object_path):
            raise invalid
        params = parse_qs(parts.query, strict_parsing=True, max_num_fields=16)
        required = ("X-Amz-Algorithm", "X-Amz-Credential", "X-Amz-Date",
                    "X-Amz-Expires", "X-Amz-SignedHeaders", "X-Amz-Signature")
        if any(len(params.get(name, ())) != 1 for name in required):
            raise invalid
        if (params["X-Amz-Algorithm"][0] != "AWS4-HMAC-SHA256"
                or params["X-Amz-SignedHeaders"][0] != "host"
                or not re.fullmatch(r"[a-f0-9]{64}", params["X-Amz-Signature"][0])
                or not re.fullmatch(r"[0-9]{1,6}", params["X-Amz-Expires"][0])):
            raise invalid
        duration = int(params["X-Amz-Expires"][0])
        if not 1 <= duration <= 604_800:
            raise invalid
        signed_at = datetime.strptime(params["X-Amz-Date"][0], "%Y%m%dT%H%M%SZ").replace(tzinfo=timezone.utc)
        expires = min(signed_at + timedelta(seconds=duration), datetime.fromisoformat(source.expiresAt.upper()))
    except (ValueError, OverflowError):
        raise invalid from None
    if expires <= datetime.now(timezone.utc):
        raise TranscribeError("RECORDING_ACCESS_FAILED")
    # Forward the signed query verbatim. S3 verifies the signature.
    return f"{parts.path}?{parts.query}"


def download(source: RecordingSource, path: Path, host: str, path_bucket: str, maximum: int, timeout: float):
    target = recording_target(source, host, path_bucket)
    digest = sha256()
    received = 0
    deadline = monotonic() + timeout
    try:
        # Direct TLS transport without redirects, environment proxies or retries.
        with closing(HTTPSConnection(host, timeout=timeout)) as connection:
            connection.request("GET", target, headers={"Accept-Encoding": "identity"})
            with connection.getresponse() as response, path.open("wb") as output:
                if response.status != 200 or response.getheader("Content-Encoding", "identity") != "identity":
                    raise TranscribeError("RECORDING_ACCESS_FAILED")
                length = response.getheader("Content-Length")
                if length is not None and int(length) > maximum:
                    raise TranscribeError("RECORDING_TOO_LARGE")
                while chunk := response.read1(CHUNK):
                    if monotonic() >= deadline:
                        raise TimeoutError
                    received += len(chunk)
                    if received > maximum:
                        raise TranscribeError("RECORDING_TOO_LARGE")
                    if received > source.sizeBytes:
                        raise TranscribeError("VALIDATION_FAILED")
                    digest.update(chunk)
                    output.write(chunk)
                if monotonic() >= deadline:
                    raise TimeoutError
    except TimeoutError:
        raise TranscribeError("AI_TIMEOUT") from None
    except (OSError, HTTPException, ValueError):
        raise TranscribeError("RECORDING_ACCESS_FAILED") from None
    if received != source.sizeBytes:
        raise TranscribeError("VALIDATION_FAILED")
    if not compare_digest(digest.digest(), b64decode(source.checksum.value)):
        raise TranscribeError("RECORDING_CHECKSUM_MISMATCH")


def validate_wav(path: Path, maximum_seconds: float, timeout: float):
    with path.open("rb") as recording:
        header = recording.read(12)
    if header[:4] not in {b"RIFF", b"RIFX", b"RF64", b"BW64"} or header[8:] != b"WAVE":
        raise TranscribeError("UNSUPPORTED_MEDIA_TYPE")
    deadline = monotonic() + timeout
    input_args = ["-protocol_whitelist", "file", "-f", "wav", "-i", str(path)]
    try:
        probe = subprocess.run(
            ["ffprobe", "-v", "error", *input_args, "-select_streams", "a:0",
             "-show_entries", "format=duration", "-of", "default=nw=1:nk=1"],
            stdout=subprocess.PIPE, stderr=subprocess.DEVNULL, timeout=timeout, check=True,
        )
        duration = float(probe.stdout)
        if not isfinite(duration) or duration <= 0:
            raise TranscribeError("UNSUPPORTED_MEDIA_TYPE")
        if duration > maximum_seconds:
            raise TranscribeError("RECORDING_TOO_LARGE")
        remaining = deadline - monotonic()
        if remaining <= 0:
            raise TranscribeError("AI_TIMEOUT")
        subprocess.run(
            ["ffmpeg", "-nostdin", "-v", "error", "-xerror", *input_args,
             "-map", "0:a:0", "-f", "null", "-"],
            stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, timeout=remaining, check=True,
        )
    except FileNotFoundError:
        raise TranscribeError("TEMPORARILY_UNAVAILABLE") from None
    except subprocess.TimeoutExpired:
        raise TranscribeError("AI_TIMEOUT") from None
    except (subprocess.CalledProcessError, ValueError):
        raise TranscribeError("UNSUPPORTED_MEDIA_TYPE") from None


def clova_error_code(payload: object) -> str:
    if isinstance(payload, dict):
        for field in ("result", "code"):
            value = payload.get(field)
            if type(value) is int and value in (400, 401):
                return str(value)
            if isinstance(value, str) and value in CLOVA_ERROR_CODES:
                return value
    return "UNCLASSIFIED"


def read_clova_error_code(response, deadline: float) -> str:
    body = bytearray()
    try:
        while len(body) <= CLOVA_DIAGNOSTIC_LIMIT and monotonic() < deadline:
            chunk = response.read1(CLOVA_DIAGNOSTIC_LIMIT + 1 - len(body))
            if not chunk:
                return clova_error_code(json.loads(body))
            body.extend(chunk)
    except (OSError, HTTPException, ValueError, RecursionError):
        pass
    return "UNCLASSIFIED"


def clova_transcript(path: Path, endpoint: str, key: str, timeout: float) -> str:
    boundary = uuid4().hex
    params = json.dumps({
        "language": "ko-KR",
        "completion": "sync",
        "fullText": True,
        "wordAlignment": False,
        "diarization": {"enable": False},
    })
    prefix = (
        f'--{boundary}\r\nContent-Disposition: form-data; name="params"\r\n'
        f'Content-Type: application/json\r\n\r\n{params}\r\n'
        f'--{boundary}\r\nContent-Disposition: form-data; name="media"; filename="recording.wav"\r\n'
        'Content-Type: audio/wav\r\n\r\n'
    ).encode()
    suffix = f"\r\n--{boundary}--\r\n".encode()
    deadline = monotonic() + timeout

    def multipart():
        yield prefix
        with path.open("rb") as audio:
            while chunk := audio.read(CHUNK):
                if monotonic() >= deadline:
                    raise TimeoutError
                yield chunk
        yield suffix

    try:
        with closing(HTTPSConnection(CLOVA_HOST, timeout=timeout)) as connection:
            connection.request("POST", endpoint, body=multipart(), headers={
                "Accept": "application/json",
                "X-CLOVASPEECH-API-KEY": key,
                "Content-Type": f"multipart/form-data; boundary={boundary}",
                "Content-Length": str(len(prefix) + path.stat().st_size + len(suffix)),
            })
            with connection.getresponse() as response:
                if response.status != 200:
                    log.warning("CLOVA Speech HTTP %d, code=%s", response.status,
                                read_clova_error_code(response, deadline))
                if response.status == 504:
                    raise TranscribeError("AI_TIMEOUT")
                if response.status in (429, 503):
                    raise TranscribeError("TEMPORARILY_UNAVAILABLE")
                if response.status == 413:
                    raise TranscribeError("RECORDING_TOO_LARGE")
                if response.status != 200:
                    raise TranscribeError("AI_PROVIDER_FAILED")
                body = bytearray()
                while chunk := response.read1(CHUNK):
                    if monotonic() >= deadline:
                        raise TimeoutError
                    body.extend(chunk)
                if monotonic() >= deadline:
                    raise TimeoutError
                if response.length not in (None, 0):
                    raise TranscribeError("AI_PROVIDER_FAILED")
                result = json.loads(body)
    except TimeoutError:
        raise TranscribeError("AI_TIMEOUT") from None
    except (OSError, HTTPException, ValueError):
        raise TranscribeError("AI_PROVIDER_FAILED") from None
    if not isinstance(result, dict) or result.get("result") != "COMPLETED":
        log.warning("CLOVA Speech HTTP 200, code=%s", clova_error_code(result))
        raise TranscribeError("AI_PROVIDER_FAILED")
    transcript = result.get("text")
    # PostgreSQL text cannot store NUL. Reject it without changing the provider's text.
    if not isinstance(transcript, str) or len(transcript) > MAX_TRANSCRIPT or "\x00" in transcript:
        raise TranscribeError("AI_PROVIDER_FAILED")
    try:
        transcript.encode("utf-8")
    except UnicodeError:
        raise TranscribeError("AI_PROVIDER_FAILED") from None
    return transcript


@router.post(
    "/internal/v1/transcribe",
    response_model=TranscribeResponse,
    dependencies=[Depends(APIKeyHeader(name="X-Internal-Api-Key", scheme_name="internalApiKey", auto_error=False))],
    responses={status: {"model": ErrorResponse} for status in (401, 403, 413, 415, 422, 500, 502, 503, 504)},
)
def transcribe(request: TranscribeRequest) -> TranscribeResponse:
    source = request.recording
    if source.contentType.lower() not in WAV_TYPES:
        raise TranscribeError("UNSUPPORTED_MEDIA_TYPE")
    host = getenv("AI_STT_S3_HOST", "")
    path_bucket = getenv("AI_STT_S3_PATH_BUCKET", "")
    clova = CLOVA_URL.fullmatch(getenv("CLOVA_SPEECH_INVOKE_URL", ""))
    key = getenv("CLOVA_SPEECH_SECRET_KEY", "")
    valid_s3 = (S3_PATH_HOST.fullmatch(host) and S3_BUCKET.fullmatch(path_bucket)
                and ".." not in path_bucket) if path_bucket else S3_HOST.fullmatch(host)
    if not valid_s3 or not clova or not re.fullmatch(r"[!-~]+", key):
        raise TranscribeError("TEMPORARILY_UNAVAILABLE")
    max_bytes = positive_setting("AI_STT_MAX_RECORDING_BYTES", 2_000_000_000)
    if not max_bytes.is_integer():
        raise TranscribeError("TEMPORARILY_UNAVAILABLE")
    max_seconds = positive_setting("AI_STT_MAX_RECORDING_SECONDS", 7200)
    download_timeout = positive_setting("AI_STT_DOWNLOAD_TIMEOUT_SECONDS")
    validation_timeout = positive_setting("AI_STT_WAV_TIMEOUT_SECONDS")
    clova_timeout = positive_setting("CLOVA_SPEECH_TIMEOUT_SECONDS")
    if source.sizeBytes > max_bytes:
        raise TranscribeError("RECORDING_TOO_LARGE")
    with TemporaryDirectory(prefix="capstone-stt-") as directory:
        audio = Path(directory) / "recording.wav"
        download(source, audio, host, path_bucket, int(max_bytes), download_timeout)
        validate_wav(audio, max_seconds, validation_timeout)
        transcript = clova_transcript(audio, clova[1] + "/recognizer/upload", key, clova_timeout)
    return TranscribeResponse(
        **request.model_dump(exclude={"recording"}), status="SUCCEEDED", transcript=transcript,
    )
