"""Validate Backend/AI OpenAPI contracts and regression cases without external I/O."""

from copy import deepcopy
from datetime import datetime
import importlib.metadata
import json
from pathlib import Path
import re

from jsonschema import Draft202012Validator, FormatChecker
from openapi_spec_validator import validate
import yaml


ROOT = Path(__file__).resolve().parents[1]
METHODS = {"get", "post", "put", "patch", "delete", "head", "options", "trace"}
checks = []
example_count = 0
case_count = 0


class UniqueKeyLoader(yaml.SafeLoader):
    """Reject silently overwritten YAML paths, methods, properties, or examples."""

    def construct_mapping(self, node, deep=False):
        result = {}
        for key_node, value_node in node.value:
            key = self.construct_object(key_node, deep=deep)
            if key in result:
                raise AssertionError("Duplicate YAML key: " + str(key))
            result[key] = self.construct_object(value_node, deep=deep)
        return result


def record(label, condition):
    if not condition:
        raise AssertionError(label)
    checks.append(label)


def read(path):
    return yaml.load(path.read_text(encoding="utf-8"), Loader=UniqueKeyLoader)


def pointer(document, reference):
    record("local reference " + reference, reference.startswith("#/"))
    value = document
    for token in reference[2:].split("/"):
        value = value[token.replace("~1", "/").replace("~0", "~")]
    return value


def resolved(document, value):
    return pointer(document, value["$ref"]) if "$ref" in value else value


def visit_refs(document, value):
    if isinstance(value, dict):
        if "$ref" in value:
            pointer(document, value["$ref"])
        for child in value.values():
            visit_refs(document, child)
    elif isinstance(value, list):
        for child in value:
            visit_refs(document, child)


def validate_value(document, schema, value, valid=True, label="schema case"):
    wrapper = dict(schema, components=document["components"])
    errors = sorted(
        Draft202012Validator(wrapper, format_checker=FormatChecker()).iter_errors(value),
        key=lambda error: str(list(error.path)),
    )
    if valid and errors:
        raise AssertionError(label + ": " + "; ".join(
            str(list(error.path)) + " " + error.message for error in errors[:3]))
    record(label, not errors if valid else bool(errors))


def case(document, name, value, valid=True, label=""):
    global case_count
    validate_value(document, {"$ref": "#/components/schemas/" + name}, value, valid,
                   label or name + " case " + str(case_count))
    case_count += 1


def examples(document, media, context):
    global example_count
    values = [media["example"]] if "example" in media else []
    values.extend(value["value"] for value in media.get("examples", {}).values() if "value" in value)
    for value in values:
        validate_value(document, media["schema"], value, label=context)
        example_count += 1


backend = read(ROOT / "docs/api/openapi.yaml")
ai = read(ROOT / "apps/ai/docs/openapi.yaml")
operation_ids = []
for title, document in [("Backend", backend), ("AI", ai)]:
    validate(document)
    record(title + " OpenAPI 3.1.1", document["openapi"] == "3.1.1")
    visit_refs(document, document)
    for route, path_item in document["paths"].items():
        for method, operation in path_item.items():
            if method not in METHODS:
                continue
            context = title + " " + method.upper() + " " + route
            operation_ids.append(operation["operationId"])
            parameters = [
                resolved(document, parameter)
                for parameter in path_item.get("parameters", []) + operation.get("parameters", [])
            ]
            record(context + " path parameters",
                   set(re.findall(r"\{([^}]+)\}", route)) ==
                   {parameter["name"] for parameter in parameters if parameter["in"] == "path"})
            record(context + " parameters unique",
                   len(parameters) == len({(parameter["in"], parameter["name"]) for parameter in parameters}))
            for parameter in parameters:
                if "example" in parameter:
                    validate_value(document, parameter["schema"], parameter["example"], label=context + " parameter")
            if "requestBody" in operation:
                body = resolved(document, operation["requestBody"])
                for media in body.get("content", {}).values():
                    examples(document, media, context + " request example")
            for status, response in operation["responses"].items():
                for media in resolved(document, response).get("content", {}).values():
                    examples(document, media, context + " response " + status + " example")

record("globally unique operationIds", len(operation_ids) == len(set(operation_ids)))
for name in ["Id", "DateTime", "WorkType", "ReceptionDraftFields", "Error", "FileChecksum"]:
    record("live shared schema " + name,
           backend["components"]["schemas"][name] == ai["components"]["schemas"][name])

record("AI transcription and structure operations",
       {"/health", "/internal/v1/transcribe", "/internal/v1/analyze"} <= set(ai["paths"]))
record("AI health public", ai["paths"]["/health"]["get"]["security"] == [])
record("AI key required", ai["security"] == [{"internalApiKey": []}])
record("AI key header", ai["components"]["securitySchemes"]["internalApiKey"]["name"] == "X-Internal-Api-Key")
record("AI origin independent", all("/api/v1" not in server["url"] for server in ai["servers"]))
for route in ["/internal/v1/transcribe", "/internal/v1/analyze"]:
    operation = ai["paths"][route]["post"]
    record(route + " synchronous 200", "200" in operation["responses"] and "202" not in operation["responses"])
    record(route + " Backend owns conflicts", "409" not in operation["responses"])
    record(route + " JSON only", set(operation["requestBody"]["content"]) == {"application/json"})
    record(route + " auth and validation responses", {"401", "403", "422", "504"} <= set(operation["responses"]))
    record(route + " no AI idempotency parameter", not operation.get("parameters"))

identity = {"analysisRequestId": "anl_01", "receptionId": "rec_01", "receptionVersion": 3,
            "recordingFileId": "file_rec_01"}
checksum = {"algorithm": "SHA256", "value": "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA="}
source = {"downloadUrl": "https://example-bucket.s3.ap-northeast-2.amazonaws.com/recording?signature=example",
          "expiresAt": "2026-11-13T14:15:00+09:00", "contentType": "audio/mp4",
          "sizeBytes": 1048576, "checksum": checksum}
transcribe_request = dict(identity, recording=source)
transcribe_result = dict(identity, status="SUCCEEDED", transcript="내일 오후에 방문해 주세요.")
analyze_request = dict(identity, transcriptFileId="file_stt_01", transcript=transcribe_result["transcript"],
                       referenceTime="2026-11-13T14:00:00+09:00", timeZone="Asia/Seoul")
analyze_result = dict(identity, transcriptFileId="file_stt_01", status="SUCCEEDED",
                      aiOriginal={"workType": "BLOCKAGE", "symptoms": "배수 불가"},
                      missingFields=["address", "desiredVisitFrom", "desiredVisitTo"])
for name, value in [("TranscribeRequest", transcribe_request), ("TranscribeSucceeded", transcribe_result),
                    ("AnalyzeRequest", analyze_request), ("AnalyzeSucceeded", analyze_result)]:
    case(ai, name, value)
    for field in identity:
        missing = deepcopy(value)
        del missing[field]
        case(ai, name, missing, False, name + " requires " + field)
    case(ai, name, dict(value, jobId="old_job"), False)
    case(ai, name, dict(value, receptionVersion=0), False)
    case(ai, name, dict(value, receptionVersion=None), False)

for bad_url in ["http://example.invalid/recording", "file:///tmp/recording", "not-a-url"]:
    case(ai, "TranscribeRequest", dict(identity, recording=dict(source, downloadUrl=bad_url)), False)
case(ai, "TranscribeRequest", dict(identity, file="old-binary-placeholder"), False)
case(ai, "TranscribeRequest", dict(identity, recording=dict(source, sizeBytes=0)), False)
for bad_checksum in [{"algorithm": "MD5", "value": checksum["value"]},
                     {"algorithm": "SHA256", "value": "a" * 64},
                     {"algorithm": "SHA256", "value": ""},
                     {"algorithm": "SHA256", "value": None}]:
    case(ai, "FileChecksum", bad_checksum, False)
case(ai, "FileChecksum", checksum)
for bad_time in ["2026-11-13T05:00:00Z", "2026-11-13T14:00:00+08:00",
                 "2026-11-13T14:00:00", "2026-11-13", "2026-13-13T14:00:00+09:00"]:
    case(ai, "AnalyzeRequest", dict(analyze_request, referenceTime=bad_time), False)
case(ai, "AnalyzeRequest", dict(analyze_request, timeZone="UTC"), False)
case(ai, "AnalyzeRequest", dict(analyze_request, transcriptFileId=None), False)
case(ai, "TranscribeSucceeded", dict(transcribe_result, transcript="가" * 100000))
case(ai, "TranscribeSucceeded", dict(transcribe_result, transcript="가" * 100001), False)
case(ai, "AnalyzeRequest", dict(analyze_request, transcript="가" * 100001), False)
case(ai, "AnalyzeSucceeded", dict(analyze_result, aiOriginal=None), False)
case(ai, "AnalyzeSucceeded", dict(analyze_result, missingFields=["notes"]), False)
case(ai, "AnalyzeSucceeded", dict(analyze_result, missingFields=["address", "address"]), False)
case(ai, "AnalyzeSucceeded", dict(analyze_result, aiOriginal={},
     missingFields=["workType", "symptoms", "address", "desiredVisitFrom", "desiredVisitTo"]))
for field, maximum in [("symptoms", 2000), ("address", 300), ("notes", 2000)]:
    case(ai, "ReceptionDraftFields", {field: "가" * maximum})
    case(ai, "ReceptionDraftFields", {field: "가" * (maximum + 1)}, False)
    case(ai, "ReceptionDraftFields", {field: None}, False)
case(ai, "ReceptionDraftFields", {"workType": "UNKNOWN"}, False)
case(ai, "ReceptionDraftFields", {"confidence": 0.9}, False)

# A JSON Schema cannot enforce these relationships in arbitrary provider output.
# Check the published examples; runtime handlers must perform these same semantic validations.
for route in ["/internal/v1/transcribe", "/internal/v1/analyze"]:
    operation = ai["paths"][route]["post"]
    sent = operation["requestBody"]["content"]["application/json"]["example"]
    received = operation["responses"]["200"]["content"]["application/json"]["example"]
    record(route + " example echoes identity", all(received[key] == sent[key] for key in identity))
    if "transcriptFileId" in sent:
        record("structure example echoes transcript file", sent["transcriptFileId"] == received["transcriptFileId"])
        required = {"workType", "symptoms", "address", "desiredVisitFrom", "desiredVisitTo"}
        record("structure example missingFields matches output",
               set(received["missingFields"]) == required - set(received["aiOriginal"]))
        fields = received["aiOriginal"]
        if {"desiredVisitFrom", "desiredVisitTo"} <= set(fields):
            record("structure example visit ordering",
                   datetime.fromisoformat(fields["desiredVisitTo"]) > datetime.fromisoformat(fields["desiredVisitFrom"]))

recording_base = "/receptions/{receptionId}/recordings"
recording = recording_base + "/{recordingFileId}"
photo_base = "/works/{workId}/photos"
photo = photo_base + "/{photoFileId}"
for base in [recording, photo]:
    record(base + " has completion", "post" in backend["paths"][base + "/completion"])
    record(base + " has renewal", "post" in backend["paths"][base + "/upload-requests"])
    record(base + " has scoped access", "get" in backend["paths"][base + "/access"])
record("automatic recording acceptance 202",
       "202" in backend["paths"][recording + "/completion"]["post"]["responses"])
record("recording completion points to attempt",
       "Location" in backend["paths"][recording + "/completion"]["post"]["responses"]["202"]["headers"])
record("Backend explicit reanalysis 202",
       "202" in backend["paths"]["/receptions/{receptionId}/analyses"]["post"]["responses"])
record("Backend transcript GET only",
       set(backend["paths"]["/receptions/{receptionId}/transcripts/{transcriptFileId}/access"]) == {"get"})
for base in [recording_base, photo_base]:
    operation = backend["paths"][base + "/upload-requests"]["post"]
    record(base + " JSON metadata only", set(operation["requestBody"]["content"]) == {"application/json"})

upload = dict(expectedVersion=1, originalFilename="call.m4a", contentType="audio/mp4",
              sizeBytes=1048576, checksum=checksum)
case(backend, "RecordingUploadRequest", upload)
case(backend, "RecordingUploadRequest", dict(upload, objectKey="attacker-selected-key"), False)
case(backend, "RecordingUploadRequest", dict(upload, file="binary"), False)
case(backend, "RecordingUploadRequest", dict(upload, sizeBytes=-1), False)
case(backend, "WorkPhotoUploadRequest", dict(
    phase="DURING", **{key: value for key, value in upload.items() if key != "expectedVersion"}))
case(backend, "WorkPhotoUploadRequest", dict(
    phase="UNKNOWN", **{key: value for key, value in upload.items() if key != "expectedVersion"}), False)
grant = backend["paths"][recording_base + "/upload-requests"]["post"]["responses"]["201"]["content"]["application/json"]["example"]
case(backend, "RecordingUploadGrant", grant)
for header in ["Content-Type", "If-None-Match", "x-amz-checksum-sha256"]:
    missing = deepcopy(grant)
    del missing["requiredHeaders"][header]
    case(backend, "RecordingUploadGrant", missing, False, "upload grant requires " + header)
changed = deepcopy(grant)
changed["requiredHeaders"]["If-None-Match"] = "not-conditional"
case(backend, "RecordingUploadGrant", changed, False)
case(backend, "CreateAnalysis", {"expectedVersion": 4, "mode": "FULL"})
case(backend, "CreateAnalysis", {"expectedVersion": 4, "mode": "FULL", "transcriptFileId": "file_stt_01"}, False)
case(backend, "CreateAnalysis", {"expectedVersion": 4, "mode": "STRUCTURE_ONLY", "transcriptFileId": "file_stt_01"})
case(backend, "CreateAnalysis", {"expectedVersion": 4, "mode": "STRUCTURE_ONLY"}, False)
case(backend, "CreateAnalysis", {"expectedVersion": 4, "mode": "AUTO"}, False)

view_route = backend["paths"]["/receptions/{receptionId}/analyses/{analysisRequestId}"]["get"]
views = view_route["responses"]["200"]["content"]["application/json"]["examples"]
pending = deepcopy(views["pending"]["value"])
success = deepcopy(views["succeeded"]["value"])
failed = deepcopy(views["structureFailed"]["value"])
for view in views.values():
    case(backend, "AnalysisView", view["value"])
case(backend, "AnalysisView", dict(pending, completedAt="2026-11-13T14:02:00+09:00"), False)
case(backend, "AnalysisView", dict(success, transcriptFileId=None), False)
case(backend, "AnalysisView", dict(success, resultApplied=False), False)
case(backend, "AnalysisView", dict(success, error=failed["error"]), False)
case(backend, "AnalysisView", dict(failed, error=None), False)
case(backend, "AnalysisView", dict(failed, resultApplied=True), False)
case(backend, "AnalysisView", dict(failed, completedAt=None), False)
case(backend, "AnalysisView", dict(failed, status="UNKNOWN"), False)
case(backend, "AnalysisView", dict(failed, transcriptFileId=None), False)
storage_failed = dict(
    pending, status="FAILED", stage="TRANSCRIPT_STORAGE", sttStatus="SUCCEEDED",
    transcriptStorageStatus="FAILED", analysisStatus="NOT_AVAILABLE",
    completedAt="2026-11-13T14:01:00+09:00",
    error={"code": "TRANSCRIPT_STORAGE_FAILED", "message": "원문 저장에 실패했습니다.", "traceId": "trace_example_01"})
case(backend, "AnalysisView", storage_failed)
case(backend, "AnalysisView", dict(pending, mode="STRUCTURE_ONLY"), False)
case(backend, "AnalysisView", dict(
    pending, mode="STRUCTURE_ONLY", sttStatus="SUCCEEDED", transcriptStorageStatus="SUCCEEDED",
    transcriptFileId="file_stt_01"))
case(backend, "AnalysisView", dict(pending, stage="COMPLETED"), False)
case(backend, "AnalysisView", dict(views["discarded"]["value"], stage="QUEUED"), False)

report = {"workDescription": "배관 세척", "diagnosis": "이물질", "totalCost": 100000,
          "beforePhotoIds": ["before_01"], "afterPhotoIds": ["after_01"]}
for name in ["ReportData", "SubmitReport"]:
    value = dict(report, expectedVersion=5) if name == "SubmitReport" else report
    case(backend, name, value)
    case(backend, name, dict(value, duringPhotoIds=[]))
    case(backend, name, dict(value, duringPhotoIds=["during_01"]))
    case(backend, name, dict(value, duringPhotoIds=["during_01", "during_01"]), False)
    case(backend, name, dict(value, duringPhotoIds=None), False)
    case(backend, name, dict(value, beforePhotoIds=[]), False)
    case(backend, name, dict(value, afterPhotoIds=[]), False)

device = backend["paths"]["/push-devices/{installationId}"]
record("device upsert and delete", set(device) == {"put", "delete"})
for operation in device.values():
    record("device administrator authorization", operation["x-roles"] == ["ADMIN"])
    record("device setters no idempotency key", not any(
        parameter.get("$ref", "").endswith("/IdempotencyKey") for parameter in operation.get("parameters", [])))
case(backend, "PushDeviceRegistration", {"platform": "ANDROID", "pushToken": "example-token"})
case(backend, "PushDeviceRegistration", {"platform": "IOS", "pushToken": "example-token"})
case(backend, "PushDeviceRegistration", {"platform": "ANDROID", "pushToken": ""}, False)
case(backend, "PushDeviceRegistration", {"platform": "ANDROID", "pushToken": "token", "userId": "other"}, False)
case(backend, "PushDeviceRegistration", {"platform": "WEB", "pushToken": "token"}, False)
record("device response omits token", "pushToken" not in backend["components"]["schemas"]["PushDevice"]["properties"])
push = backend["x-reception-analysis-push"]["example"]
case(backend, "ReceptionAnalysisPushData", push)
case(backend, "ReceptionAnalysisPushData", dict(push, eventType="RECEPTION_ANALYSIS_FAILED"))
case(backend, "ReceptionAnalysisPushData", dict(push, transcript="sensitive"), False)
case(backend, "ReceptionAnalysisPushData", dict(push, downloadUrl="https://example.invalid/signed"), False)
case(backend, "ReceptionAnalysisPushData", dict(push, receptionId=1), False)
record("FCM data all strings", all(isinstance(value, str) for value in push.values()))
record("recording access preserves scoped driver authorization",
       backend["paths"][recording + "/access"]["get"]["x-roles"] == ["ADMIN", "DRIVER"])
record("transcript remains administrator-only",
       backend["paths"]["/receptions/{receptionId}/transcripts/{transcriptFileId}/access"]["get"]["x-roles"] == ["ADMIN"])

print(json.dumps({
    "checks_passed": len(checks),
    "examples_validated": example_count,
    "schema_cases": case_count,
    "operations": len(operation_ids),
    "tools": {name: importlib.metadata.version(name)
              for name in ["PyYAML", "openapi-spec-validator", "jsonschema"]},
    "scope": "Static contracts only; no S3, AI, worker, FCM, database, or application execution.",
}, ensure_ascii=False, indent=2))
