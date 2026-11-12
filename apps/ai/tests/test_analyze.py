import json
import logging

import httpx
import pytest
from fastapi.testclient import TestClient
from openai import OpenAI

from app import consultation
from app.main import app

PATH = "/internal/v1/analyze"
REQUEST = {
    "jobId": "job_example",
    "receptionId": "rec_example",
    "receptionVersion": 1,
    "transcript": "싱크대가 막혔어요. 예시로 10에 내일 오후 2시부터 4시 사이에 와 주세요. 방문 전 연락 주세요.",
    "referenceTime": "2026-11-06T14:00:00+09:00",
    "timeZone": "Asia/Seoul",
}
EMPTY_EXTRACTION = {
    "workType": None,
    "symptoms": None,
    "address": None,
    "desiredVisitFrom": None,
    "desiredVisitTo": None,
    "notes": None,
}
COMPLETE_EXTRACTION = {
    "workType": "BLOCKAGE",
    "symptoms": "싱크대 배수 불가",
    "address": "예시로 10",
    "desiredVisitFrom": "2026-11-07T14:00:00+09:00",
    "desiredVisitTo": "2026-11-07T16:00:00+09:00",
    "notes": "방문 전 연락",
}


@pytest.fixture
def client():
    with TestClient(app) as test_client:
        yield test_client


@pytest.fixture
def mock_openai(monkeypatch):
    def install(handler):
        def create_client(**kwargs):
            assert kwargs == {"timeout": 30.0, "max_retries": 0}
            return OpenAI(
                api_key="test-only",
                base_url="https://example.invalid/v1",
                http_client=httpx.Client(transport=httpx.MockTransport(handler)),
                **kwargs,
            )

        monkeypatch.setattr(consultation, "OpenAI", create_client)

    return install


def model_response(extraction):
    return httpx.Response(
        200,
        json={
            "id": "response_example",
            "object": "response",
            "status": "completed",
            "output": [{
                "id": "message_example",
                "type": "message",
                "role": "assistant",
                "status": "completed",
                "content": [{
                    "type": "output_text",
                    "text": json.dumps(extraction),
                    "annotations": [],
                }],
            }],
        },
    )


def assert_error(response, status, code):
    assert response.status_code == status
    error = response.json()
    assert set(error) == {"code", "message", "traceId"}
    assert error["code"] == code
    assert error["message"]
    assert error["traceId"]


def test_analyze_normal_consultation_and_relative_dates(client, mock_openai, caplog):
    def respond(request):
        assert request.url.path == "/v1/responses"
        body = json.loads(request.content)
        assert body["model"] == "gpt-5.4"
        assert body["store"] is False
        assert body["input"] == REQUEST["transcript"]
        assert REQUEST["referenceTime"] in body["instructions"]
        assert REQUEST["timeZone"] in body["instructions"]
        assert REQUEST["transcript"] not in body["instructions"]
        assert REQUEST["receptionId"] not in request.content.decode()
        output_format = body["text"]["format"]
        assert output_format["type"] == "json_schema"
        assert output_format["strict"] is True
        assert set(output_format["schema"]["required"]) == set(COMPLETE_EXTRACTION)
        assert output_format["schema"]["additionalProperties"] is False
        for schema in output_format["schema"]["properties"].values():
            assert {"type": "null"} in schema["anyOf"]
        return model_response(COMPLETE_EXTRACTION)

    mock_openai(respond)
    with caplog.at_level(logging.DEBUG):
        response = client.post(PATH, json=REQUEST)
    assert response.status_code == 200
    assert response.json() == {
        "jobId": REQUEST["jobId"],
        "receptionId": REQUEST["receptionId"],
        "receptionVersion": REQUEST["receptionVersion"],
        "status": "SUCCEEDED",
        "aiOriginal": COMPLETE_EXTRACTION,
        "missingFields": [],
    }
    assert REQUEST["transcript"] not in caplog.text
    assert "test-only" not in caplog.text


@pytest.mark.parametrize(
    ("extraction", "missing"),
    [
        ({"workType": "BLOCKAGE", "symptoms": "싱크대 배수 불가"},
         ["address", "desiredVisitFrom", "desiredVisitTo"]),
        ({"symptoms": "물이 고여 있음", "notes": ""},
         ["workType", "address", "desiredVisitFrom", "desiredVisitTo"]),
        ({}, ["workType", "symptoms", "address", "desiredVisitFrom", "desiredVisitTo"]),
    ],
)
def test_analyze_omits_unknown_fields(client, mock_openai, extraction, missing):
    mock_openai(lambda _: model_response({**EMPTY_EXTRACTION, **extraction}))
    response = client.post(PATH, json={**REQUEST, "transcript": "추가 확인이 필요해요."})
    assert response.status_code == 200
    assert response.json()["aiOriginal"] == extraction
    assert response.json()["missingFields"] == missing


@pytest.mark.parametrize(
    "changes",
    [
        {"jobId": ""},
        {"receptionId": "x" * 65},
        {"receptionVersion": 0},
        {"receptionVersion": "1"},
        {"transcript": "가" * 100_001},
        {"transcript": None},
        {"referenceTime": "2026-11-06T14:00:00"},
        {"referenceTime": "2026-02-30T14:00:00+09:00"},
        {"referenceTime": "2026-11-06T14:00:00+09:99"},
        {"timeZone": "Invalid/Zone"},
        {"finalData": {}},
    ],
)
def test_invalid_input_does_not_call_provider(client, mock_openai, changes):
    def unexpected(_):
        pytest.fail("입력 검증 실패 시 모델을 호출했습니다.")

    mock_openai(unexpected)
    response = client.post(PATH, json={**REQUEST, **changes})
    assert_error(response, 422, "VALIDATION_FAILED")
    assert REQUEST["transcript"] not in response.text


@pytest.mark.parametrize("body", [b'{"transcript":', b'{"transcript":"\xff"}'])
def test_invalid_json_uses_contract_error(client, mock_openai, body):
    def unexpected(_):
        pytest.fail("잘못된 JSON으로 모델을 호출했습니다.")

    mock_openai(unexpected)
    response = client.post(PATH, content=body, headers={"content-type": "application/json"})
    assert_error(response, 422, "VALIDATION_FAILED")


@pytest.mark.parametrize("mode", ["http_error", "timeout", "failed"])
def test_provider_failure_is_sanitized(client, mock_openai, caplog, mode):
    def respond(request):
        if mode == "timeout":
            raise httpx.ReadTimeout(REQUEST["transcript"], request=request)
        if mode == "failed":
            body = model_response(COMPLETE_EXTRACTION).json()
            body.update(status="failed", output=[], error={"message": REQUEST["transcript"]})
            return httpx.Response(200, json=body)
        return httpx.Response(503, json={"error": {"message": REQUEST["transcript"]}})

    mock_openai(respond)
    with caplog.at_level(logging.DEBUG):
        response = client.post(PATH, json=REQUEST)
    assert_error(response, 503, "TEMPORARILY_UNAVAILABLE")
    assert REQUEST["transcript"] not in response.text
    assert REQUEST["transcript"] not in caplog.text
    assert "test-only" not in caplog.text


@pytest.mark.parametrize(
    "changes",
    [
        {"workType": "UNKNOWN"},
        {"symptoms": ""},
        {"address": ""},
        {"address": "가" * 301},
        {"callerPhone": "synthetic"},
        {"desiredVisitFrom": ""},
        {"desiredVisitFrom": "2026-11-07T14:00:00"},
        {"desiredVisitFrom": "2026-02-30T14:00:00+09:00"},
        {"desiredVisitTo": "2026-11-07T13:00:00+09:00"},
    ],
)
def test_invalid_model_output_is_not_returned(client, mock_openai, caplog, changes):
    model_data = {**COMPLETE_EXTRACTION, "notes": "모델 응답 비공개 표식", **changes}
    mock_openai(lambda _: model_response(model_data))
    with caplog.at_level(logging.DEBUG):
        response = client.post(PATH, json=REQUEST)
    assert_error(response, 500, "INTERNAL_ERROR")
    assert model_data["notes"] not in response.text
    assert model_data["notes"] not in caplog.text


@pytest.mark.parametrize(
    "mode", ["refusal", "mixed_refusal", "incomplete", "incomplete_message", "malformed", "invalid_utf8"]
)
def test_unusable_provider_response_is_sanitized(client, mock_openai, mode):
    def respond(_):
        if mode == "malformed":
            return httpx.Response(200, json={"unexpected": True})
        if mode == "invalid_utf8":
            return httpx.Response(200, content=b'{"output":"\xff"}', headers={"content-type": "application/json"})
        body = model_response(COMPLETE_EXTRACTION).json()
        message = body["output"][0]
        refusal = {"type": "refusal", "refusal": "거절 예시"}
        if mode == "refusal":
            message["content"] = [refusal]
        elif mode == "mixed_refusal":
            message["content"].append(refusal)
        elif mode == "incomplete_message":
            message["status"] = "incomplete"
        elif mode == "incomplete":
            body["status"] = "incomplete"
        return httpx.Response(200, json=body)

    mock_openai(respond)
    assert_error(client.post(PATH, json=REQUEST), 500, "INTERNAL_ERROR")


def test_provider_metadata_does_not_leak_in_serialization_warnings(
    client, mock_openai, recwarn, caplog
):
    marker = "비정상 모델 메타데이터 비공개 표식"

    def respond(_):
        body = model_response(EMPTY_EXTRACTION).json()
        body["output"][0]["content"][0]["annotations"] = marker
        return httpx.Response(200, json=body)

    mock_openai(respond)
    with caplog.at_level(logging.DEBUG):
        response = client.post(PATH, json=REQUEST)
    assert response.status_code == 200
    assert response.json()["aiOriginal"] == {}
    assert not recwarn.list
    assert marker not in response.text
    assert marker not in caplog.text
