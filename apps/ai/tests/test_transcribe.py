from base64 import b64encode
from datetime import datetime, timedelta, timezone
from hashlib import sha256
from io import BytesIO
import json
from pathlib import Path
from shutil import which
from struct import pack
import subprocess
from types import SimpleNamespace
from urllib.parse import urlencode
import wave

from fastapi.testclient import TestClient
import pytest

from app.main import app
from app import transcribe as stt

HOST = "example-bucket.s3.ap-northeast-2.amazonaws.com"
KEY = "test-internal-key"
AUTH = {"X-Internal-Api-Key": KEY}
ENDPOINT = "/internal/v1/transcribe"
ORIGINAL = "  싱크대가 막혔습니다.\r\n 다음 줄\t "
IDENTITY = {
    "analysisRequestId": "anl_17",
    "receptionId": "rec_12",
    "receptionVersion": 3,
    "recordingFileId": "file_rec_25",
}


def wav_bytes():
    output = BytesIO()
    with wave.open(output, "wb") as recording:
        recording.setparams((1, 2, 8000, 0, "NONE", "not compressed"))
        recording.writeframes(b"\x00\x00" * 800)
    return output.getvalue()


def payload(audio, *, signed_at=None):
    now = datetime.now(timezone.utc)
    signed_at = signed_at or now
    query = urlencode({
        "X-Amz-Algorithm": "AWS4-HMAC-SHA256",
        "X-Amz-Credential": "TEST/20261119/ap-northeast-2/s3/aws4_request",
        "X-Amz-Date": signed_at.strftime("%Y%m%dT%H%M%SZ"),
        "X-Amz-Expires": "600",
        "X-Amz-Security-Token": "example-session-token",
        "X-Amz-SignedHeaders": "host",
        "X-Amz-Signature": "0" * 64,
    })
    return {
        **IDENTITY,
        "recording": {
            "downloadUrl": f"https://{HOST}/recordings/12/b97cd7ee-db24-4d02-a3e5-3a3844ab3123?{query}",
            "expiresAt": (now + timedelta(minutes=10)).astimezone(
                timezone(timedelta(hours=9)),
            ).isoformat(),
            "contentType": "audio/wav",
            "sizeBytes": len(audio),
            "checksum": {"algorithm": "SHA256", "value": b64encode(sha256(audio).digest()).decode()},
        },
    }


class Response(BytesIO):
    def __init__(self, status, body=b"", headers=None):
        super().__init__(body)
        self.status = status
        self.headers = headers or {}
        self.length = None

    def getheader(self, name, default=None):
        return self.headers.get(name, default)


@pytest.fixture
def setup(monkeypatch):
    for name, value in {
        "AI_INTERNAL_API_KEY": KEY,
        "AI_STT_S3_HOST": HOST,
        "AI_STT_S3_PATH_BUCKET": "",
        "AI_STT_MAX_RECORDING_BYTES": "4000",
        "AI_STT_MAX_RECORDING_SECONDS": "1",
        "AI_STT_DOWNLOAD_TIMEOUT_SECONDS": "2",
        "AI_STT_WAV_TIMEOUT_SECONDS": "3",
        "CLOVA_SPEECH_INVOKE_URL": "https://clovaspeech-gw.ncloud.com/external/v1/123/example",
        "CLOVA_SPEECH_SECRET_KEY": "test-clova-key",
        "CLOVA_SPEECH_TIMEOUT_SECONDS": "4",
    }.items():
        monkeypatch.setenv(name, value)

    audio = wav_bytes()
    state = SimpleNamespace(
        audio=audio, calls=[], wav_calls=[], s3_status=200, clova_status=200,
        clova_result={"result": "COMPLETED", "text": ORIGINAL}, clova_timeout=False,
        s3_timeout=False, clova_remaining=None,
        duration=b"0.100000\n", broken_decoder=False, request=payload(audio),
    )

    class Connection:
        def __init__(self, host, timeout):
            self.host = host
            self.timeout = timeout

        def request(self, method, target, headers, body=None):
            self.method = method
            body = b"".join(body) if body is not None else None
            state.calls.append((self.host, self.timeout, method, target, headers, body))

        def getresponse(self):
            if self.method == "GET":
                if state.s3_timeout:
                    raise TimeoutError("private S3 URL")
                return Response(state.s3_status, state.audio)
            if state.clova_timeout:
                raise TimeoutError("private provider details")
            response = Response(state.clova_status, json.dumps(state.clova_result).encode())
            response.length = state.clova_remaining
            return response

        def close(self):
            pass

    def run(args, **kwargs):
        path = Path(args[args.index("-i") + 1])
        assert path.read_bytes() == state.audio
        state.wav_calls.append((args[0], path))
        if args[0] == "ffmpeg" and state.broken_decoder:
            raise subprocess.CalledProcessError(1, args)
        return subprocess.CompletedProcess(args, 0, stdout=state.duration)

    monkeypatch.setattr(stt, "HTTPSConnection", Connection)
    monkeypatch.setattr(stt.subprocess, "run", run)
    with TestClient(app) as client:
        yield client, state
    assert all(not path.exists() for _, path in state.wav_calls)


def assert_error(response, status, code):
    assert response.status_code == status, response.text
    result = response.json()
    assert set(result) == {"code", "message", "traceId"}
    assert result["code"] == code
    assert result["message"] and result["traceId"]


@pytest.mark.parametrize("mime", sorted(stt.WAV_TYPES))
def test_ack29_roundtrip(setup, mime):
    client, state = setup
    state.request["recording"]["contentType"] = mime
    response = client.post(ENDPOINT, json=state.request, headers=AUTH)
    assert response.status_code == 200, response.text
    assert response.headers["content-type"] == "application/json"
    assert response.json() == {**IDENTITY, "status": "SUCCEEDED", "transcript": ORIGINAL}
    get, post = state.calls
    assert get[:4] == (
        HOST, 2, "GET", state.request["recording"]["downloadUrl"].removeprefix(f"https://{HOST}"),
    )
    assert "X-CLOVASPEECH-API-KEY" not in get[4]
    assert post[:4] == ("clovaspeech-gw.ncloud.com", 4, "POST", "/external/v1/123/example/recognizer/upload")
    assert post[4]["X-CLOVASPEECH-API-KEY"] == "test-clova-key"
    assert int(post[4]["Content-Length"]) == len(post[5])
    assert b'name="media"; filename="recording.wav"' in post[5]
    assert b'"completion": "sync"' in post[5]
    assert state.audio in post[5]
    assert [name for name, _ in state.wav_calls] == ["ffprobe", "ffmpeg"]


@pytest.mark.parametrize("headers", [{}, {"X-Internal-Api-Key": "wrong"}, list(AUTH.items()) * 2])
def test_auth_precedes_body_parsing(setup, headers):
    client, state = setup
    assert_error(client.post(ENDPOINT, content="{", headers=headers), 401, "UNAUTHENTICATED")
    assert state.calls == []


def test_authenticated_key_without_transcribe_permission(setup, monkeypatch):
    client, state = setup
    monkeypatch.setenv("AI_INTERNAL_NO_TRANSCRIBE_API_KEY", "restricted-test-key")
    response = client.post(ENDPOINT, content="{", headers={"X-Internal-Api-Key": "restricted-test-key"})
    assert_error(response, 403, "FORBIDDEN")
    assert state.calls == []


def test_missing_configuration(setup, monkeypatch):
    client, state = setup
    monkeypatch.delenv("CLOVA_SPEECH_TIMEOUT_SECONDS")
    assert_error(client.post(ENDPOINT, json=state.request, headers=AUTH), 503, "TEMPORARILY_UNAVAILABLE")
    assert state.calls == []


@pytest.mark.parametrize("change", [
    {"analysisRequestId": None}, {"receptionVersion": "3"}, {"receptionVersion": True},
    {"jobId": "extra"}, {"recording": {}},
])
def test_strict_backend_schema(setup, change):
    client, state = setup
    assert_error(client.post(ENDPOINT, json=state.request | change, headers=AUTH), 422, "VALIDATION_FAILED")
    assert state.calls == []


@pytest.mark.parametrize("url", [
    "http://example-bucket.s3.ap-northeast-2.amazonaws.com/",
    "https://127.0.0.1/", "https://169.254.169.254/",
    f"https://{HOST}.example.net/", f"https://{HOST}@127.0.0.1/",
    f"https://{HOST}:443/recordings/12/test", f"https://{HOST}/other/private.wav",
    f"https://{HOST}\\@127.0.0.1/",
])
def test_rejects_untrusted_url_without_network(setup, url):
    client, state = setup
    state.request["recording"]["downloadUrl"] = url
    assert_error(client.post(ENDPOINT, json=state.request, headers=AUTH), 422, "VALIDATION_FAILED")
    assert state.calls == []


@pytest.mark.parametrize("bucket", ["recordings.example", "other.example"])
def test_path_style_requires_exact_bucket(setup, monkeypatch, bucket):
    client, state = setup
    host = "s3.ap-northeast-2.amazonaws.com"
    monkeypatch.setenv("AI_STT_S3_HOST", host)
    monkeypatch.setenv("AI_STT_S3_PATH_BUCKET", "recordings.example")
    state.request["recording"]["downloadUrl"] = state.request["recording"]["downloadUrl"].replace(
        HOST, f"{host}/{bucket}",
    )
    response = client.post(ENDPOINT, json=state.request, headers=AUTH)
    if bucket == "recordings.example":
        assert response.status_code == 200
        assert state.calls[0][3].startswith(f"/{bucket}/recordings/")
    else:
        assert_error(response, 422, "VALIDATION_FAILED")
        assert state.calls == []


@pytest.mark.parametrize("source", ["body", "signature"])
def test_expired_url(setup, source):
    client, state = setup
    past = datetime.now(timezone.utc) - timedelta(hours=1)
    if source == "body":
        state.request["recording"]["expiresAt"] = past.isoformat()
    else:
        state.request = payload(state.audio, signed_at=past)
    assert_error(client.post(ENDPOINT, json=state.request, headers=AUTH), 502, "RECORDING_ACCESS_FAILED")
    assert state.calls == []


@pytest.mark.parametrize("status", [302, 403, 404, 500])
def test_download_failure_is_not_retried_or_redirected(setup, status):
    client, state = setup
    state.s3_status = status
    assert_error(client.post(ENDPOINT, json=state.request, headers=AUTH), 502, "RECORDING_ACCESS_FAILED")
    assert len(state.calls) == 1


def test_download_timeout_is_reported_to_backend(setup):
    client, state = setup
    state.s3_timeout = True
    response = client.post(ENDPOINT, json=state.request, headers=AUTH)
    assert_error(response, 504, "AI_TIMEOUT")
    assert "private S3 URL" not in response.text
    assert [call[2] for call in state.calls] == ["GET"]


def test_invalid_rfc3339_offset_is_rejected(setup):
    client, state = setup
    state.request["recording"]["expiresAt"] = "2026-11-19T15:00:00+00:99"
    assert_error(client.post(ENDPOINT, json=state.request, headers=AUTH), 422, "VALIDATION_FAILED")
    assert state.calls == []


@pytest.mark.parametrize("source", ["declared", "stream", "duration"])
def test_recording_limit(setup, source):
    client, state = setup
    if source == "declared":
        state.request["recording"]["sizeBytes"] = 4001
    elif source == "stream":
        state.audio = b"0" * 4001
    else:
        state.duration = b"2"
    assert_error(client.post(ENDPOINT, json=state.request, headers=AUTH), 413, "RECORDING_TOO_LARGE")
    assert [call[2] for call in state.calls] == ([] if source == "declared" else ["GET"])


def test_checksum_mismatch(setup):
    client, state = setup
    state.audio = state.audio[:-1] + b"\x01"
    assert_error(client.post(ENDPOINT, json=state.request, headers=AUTH), 422, "RECORDING_CHECKSUM_MISMATCH")
    assert len(state.calls) == 1


def test_download_size_mismatch(setup):
    client, state = setup
    state.audio = state.audio[:-1]
    assert_error(client.post(ENDPOINT, json=state.request, headers=AUTH), 422, "VALIDATION_FAILED")
    assert len(state.calls) == 1


@pytest.mark.parametrize("source", ["mime", "header", "codec"])
def test_invalid_wav(setup, source):
    client, state = setup
    if source == "mime":
        state.request["recording"]["contentType"] = "audio/mp4"
    elif source == "header":
        state.audio = b"not a WAV file"
        state.request = payload(state.audio)
    else:
        state.broken_decoder = True
    assert_error(client.post(ENDPOINT, json=state.request, headers=AUTH), 415, "UNSUPPORTED_MEDIA_TYPE")
    assert [call[2] for call in state.calls] == ([] if source == "mime" else ["GET"])


@pytest.mark.parametrize(("status", "timeout", "expected", "code"), [
    (502, False, 502, "AI_PROVIDER_FAILED"),
    (429, False, 503, "TEMPORARILY_UNAVAILABLE"),
    (504, False, 504, "AI_TIMEOUT"),
    (200, True, 504, "AI_TIMEOUT"),
])
def test_provider_failure_is_not_retried(setup, status, timeout, expected, code):
    client, state = setup
    state.clova_status = status
    state.clova_timeout = timeout
    response = client.post(ENDPOINT, json=state.request, headers=AUTH)
    assert_error(response, expected, code)
    assert "private provider details" not in response.text
    assert [call[2] for call in state.calls] == ["GET", "POST"]


def test_blank_transcript_is_left_for_backend(setup):
    client, state = setup
    state.clova_result["text"] = " \r\n\t"
    response = client.post(ENDPOINT, json=state.request, headers=AUTH)
    assert response.json() == {**IDENTITY, "status": "SUCCEEDED", "transcript": " \r\n\t"}


@pytest.mark.parametrize("result", [
    {"result": "PROCESSING", "text": ORIGINAL},
    {"result": "COMPLETED"},
    {"result": "COMPLETED", "text": None},
    {"result": "COMPLETED", "text": "transcript\x00"},
    {"result": "COMPLETED", "text": "\ud800"},
    {"result": "COMPLETED", "text": "가" * 100_001},
])
def test_invalid_clova_result_is_rejected_without_retry(setup, result):
    client, state = setup
    state.clova_result = result
    assert_error(client.post(ENDPOINT, json=state.request, headers=AUTH), 502, "AI_PROVIDER_FAILED")
    assert [call[2] for call in state.calls] == ["GET", "POST"]


def test_truncated_provider_response_is_rejected(setup):
    client, state = setup
    state.clova_remaining = 2
    assert_error(client.post(ENDPOINT, json=state.request, headers=AUTH), 502, "AI_PROVIDER_FAILED")


def test_clova_body_read_honors_elapsed_budget(setup, monkeypatch):
    client, state = setup
    clock = [0]
    read1 = Response.read1

    def slow_read(response, size):
        chunk = read1(response, size)
        if state.calls[-1][2] == "POST":
            clock[0] = 5
        return chunk

    monkeypatch.setattr(stt, "monotonic", lambda: clock[0])
    monkeypatch.setattr(Response, "read1", slow_read)
    assert_error(client.post(ENDPOINT, json=state.request, headers=AUTH), 504, "AI_TIMEOUT")
    assert [call[2] for call in state.calls] == ["GET", "POST"]


def test_transcript_limit_counts_unicode_code_points(setup):
    client, state = setup
    transcript = "😀" * 100_000
    state.clova_result["text"] = transcript
    response = client.post(ENDPOINT, json=state.request, headers=AUTH)
    assert response.status_code == 200
    assert response.json()["transcript"] == transcript


@pytest.mark.skipif(not (which("ffmpeg") and which("ffprobe")), reason="FFmpeg tools are unavailable")
def test_real_decoder_accepts_pcm_and_rejects_corrupt_codec(tmp_path):
    path = tmp_path / "recording.wav"
    data = wav_bytes()
    path.write_bytes(data)
    stt.validate_wav(path, 1, 5)
    format_tag = data.index(b"fmt ") + 8
    path.write_bytes(data[:format_tag] + b"\xff\xff" + data[format_tag + 2:])
    with pytest.raises(stt.TranscribeError, match="UNSUPPORTED_MEDIA_TYPE"):
        stt.validate_wav(path, 1, 5)


@pytest.mark.skipif(not (which("ffmpeg") and which("ffprobe")), reason="FFmpeg tools are unavailable")
@pytest.mark.parametrize("container", [b"RIFX", b"RF64", b"BW64"])
def test_real_decoder_supports_backend_wav_containers(tmp_path, container):
    path = tmp_path / "recording.wav"
    pcm = b"\x00\x00" * 800
    if container == b"RIFX":
        fmt = pack(">IHHIIHH", 16, 1, 1, 8000, 16000, 2, 16)
        header = container + pack(">I", 36 + len(pcm)) + b"WAVEfmt " + fmt
        header += b"data" + pack(">I", len(pcm))
    else:
        fmt = pack("<IHHIIHH", 16, 1, 1, 8000, 16000, 2, 16)
        sizes = pack("<IQQQI", 28, 72 + len(pcm), len(pcm), 800, 0)
        header = container + b"\xff" * 4 + b"WAVEds64" + sizes
        header += b"fmt " + fmt + b"data" + b"\xff" * 4
    path.write_bytes(header + pcm)
    stt.validate_wav(path, 1, 5)
