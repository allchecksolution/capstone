import json

from test_analyze import EMPTY_EXTRACTION, PATH, REQUEST, mock_openai, model_response
from test_transcribe import AUTH, ENDPOINT, setup


def test_transcribe_to_analyze_contract(mock_openai, setup):
    client, stt = setup
    original = "  보일러 점화가 안 됩니다.\r\n"
    stt.clova_result["text"] = original

    def respond(request):
        assert json.loads(request.content)["input"] == original
        return model_response({**EMPTY_EXTRACTION, "workType": "BOILER"})

    mock_openai(respond)
    raw = client.post(ENDPOINT, json=stt.request, headers=AUTH)
    assert raw.status_code == 200

    # Mirror the Backend request after it stores the original and obtains a DB ID.
    analysis = {key: value for key, value in raw.json().items() if key != "status"}
    analysis.update(
        transcriptFileId="file_stt_7",
        referenceTime=REQUEST["referenceTime"],
        timeZone=REQUEST["timeZone"],
    )
    response = client.post(PATH, json=analysis, headers=AUTH)
    assert response.status_code == 200
    result = response.json()
    for key in ("analysisRequestId", "receptionId", "receptionVersion", "recordingFileId", "transcriptFileId"):
        assert result[key] == analysis[key]
    assert result["aiOriginal"] == {"workType": "BOILER"}
