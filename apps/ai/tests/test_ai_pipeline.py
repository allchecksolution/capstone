import json

import httpx
import pytest

from test_analyze import EMPTY_EXTRACTION, PATH, assert_error, mock_openai, model_response
from test_transcribe import AUTH, ENDPOINT, setup


REFERENCE_TIME = "2026-11-06T14:00:00+09:00"
TIME_ZONE = "Asia/Seoul"
BASE_TRANSCRIPT = "  보일러 점화가 안 됩니다."
ADDRESS = "서울시 가상로 10"
SYMPTOMS = "보일러 점화 불량"


def transcribe_for_analysis(client, stt, original):
    stt.clova_result["text"] = original
    response = client.post(ENDPOINT, json=stt.request, headers=AUTH)
    assert response.status_code == 200
    transcript_response = response.json()
    assert transcript_response["status"] == "SUCCEEDED"
    assert transcript_response["transcript"] == original

    # Mirror the Backend request after it stores the original and obtains a DB ID.
    return {
        **{key: value for key, value in transcript_response.items() if key != "status"},
        "transcriptFileId": "file_stt_7",
        "referenceTime": REFERENCE_TIME,
        "timeZone": TIME_ZONE,
    }


@pytest.mark.parametrize(("include_address", "include_visit", "missing"), [
    (True, True, []),
    (False, True, ["address"]),
    (True, False, ["desiredVisitFrom", "desiredVisitTo"]),
    (False, False, ["address", "desiredVisitFrom", "desiredVisitTo"]),
])
def test_transcribe_to_analyze_contract(mock_openai, setup, include_address, include_visit, missing):
    client, stt = setup
    original = BASE_TRANSCRIPT
    extraction = {**EMPTY_EXTRACTION, "workType": "BOILER", "symptoms": SYMPTOMS}
    expected = {"workType": "BOILER", "symptoms": SYMPTOMS}
    if include_address:
        original += f" 주소는 {ADDRESS}입니다."
        extraction["address"] = expected["address"] = ADDRESS
    if include_visit:
        original += " 내일 오후 2시부터 4시 사이에 와 주세요."
        extraction.update(desiredVisitFrom="2026-11-07T05:00:00Z", desiredVisitTo="2026-11-07T07:00:00Z")
        expected.update(desiredVisitFrom="2026-11-07T14:00:00+09:00", desiredVisitTo="2026-11-07T16:00:00+09:00")
    original += "\r\n"

    def respond(request):
        body = json.loads(request.content)
        assert body["input"] == original
        assert f"referenceTime={REFERENCE_TIME}" in body["instructions"]
        assert f"timeZone={TIME_ZONE}" in body["instructions"]
        return model_response(extraction)

    mock_openai(respond)
    analysis = transcribe_for_analysis(client, stt, original)
    response = client.post(PATH, json=analysis, headers=AUTH)
    assert response.status_code == 200
    result = response.json()
    for key in ("analysisRequestId", "receptionId", "receptionVersion", "recordingFileId", "transcriptFileId"):
        assert result[key] == analysis[key]
    assert result["status"] == "SUCCEEDED"
    assert result["aiOriginal"] == expected
    assert result["missingFields"] == missing


@pytest.mark.parametrize(("failure", "status", "code"), [
    ("timeout", 504, "AI_TIMEOUT"),
    ("invalid_visit_range", 500, "INTERNAL_ERROR"),
])
def test_analysis_failure_returns_no_reception_result(mock_openai, setup, failure, status, code):
    client, stt = setup
    original = BASE_TRANSCRIPT + "\r\n"
    provider_calls = []

    def respond(request):
        body = json.loads(request.content)
        provider_calls.append(body["input"])
        assert body["input"] == original
        if failure == "timeout":
            raise httpx.ReadTimeout(original, request=request)
        return model_response({
            **EMPTY_EXTRACTION,
            "workType": "BOILER",
            "desiredVisitFrom": "2026-11-07T16:00:00+09:00",
            "desiredVisitTo": "2026-11-07T14:00:00+09:00",
        })

    mock_openai(respond)
    analysis = transcribe_for_analysis(client, stt, original)
    response = client.post(PATH, json=analysis, headers=AUTH)
    assert_error(response, status, code)
    assert original not in response.text
    assert provider_calls == [original]
