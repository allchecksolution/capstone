import re
from datetime import datetime
from typing import Annotated, Any, Literal, NotRequired, TypedDict
from zoneinfo import ZoneInfo

from pydantic_core import PydanticCustomError

from pydantic import (
    AfterValidator,
    BaseModel,
    ConfigDict,
    Field,
    StringConstraints,
    WithJsonSchema,
    model_validator,
)

RFC3339 = re.compile(
    r"[0-9]{4}-[0-9]{2}-[0-9]{2}[Tt][0-9]{2}:[0-9]{2}:[0-9]{2}"
    r"(?:\.[0-9]+)?(?:[Zz]|[+-](?:[01][0-9]|2[0-3]):[0-5][0-9])"
)


def validate_datetime(value: str) -> str:
    if not RFC3339.fullmatch(value):
        raise ValueError("시간대를 포함한 RFC 3339 시각이 필요합니다.")
    datetime.fromisoformat(value.upper())
    return value


DateTime = Annotated[
    str,
    StringConstraints(strict=True),
    AfterValidator(validate_datetime),
    WithJsonSchema({"type": "string", "format": "date-time"}),
]
Id = Annotated[str, StringConstraints(strict=True, min_length=1, max_length=64)]
WorkType = Literal["EXTERIOR_LEAK", "INTERIOR_LEAK", "BLOCKAGE", "BOILER", "WATERPROOFING"]
NonEmptyString = Annotated[str, StringConstraints(min_length=1)]
Symptoms = Annotated[str, StringConstraints(min_length=1, max_length=2000)]
Address = Annotated[str, StringConstraints(min_length=1, max_length=300)]
Notes = Annotated[str, StringConstraints(max_length=2000)]
RequiredDraftField = Literal[
    "workType", "symptoms", "address", "desiredVisitFrom", "desiredVisitTo"
]
REQUIRED_DRAFT_FIELDS: tuple[RequiredDraftField, ...] = (
    "workType", "symptoms", "address", "desiredVisitFrom", "desiredVisitTo"
)


class AnalyzeRequest(BaseModel):
    model_config = ConfigDict(extra="forbid", strict=True)

    analysisRequestId: Id
    recordingFileId: Id
    transcriptFileId: Id
    receptionId: Id
    receptionVersion: Annotated[int, Field(ge=1)]
    transcript: Annotated[str, StringConstraints(max_length=100_000)]
    referenceTime: DateTime
    timeZone: Literal["Asia/Seoul"]

    @model_validator(mode="after")
    def validate_reference_time_offset(self) -> "AnalyzeRequest":
        reference_time = datetime.fromisoformat(self.referenceTime.upper())
        local_time = reference_time.astimezone(ZoneInfo(self.timeZone))
        if reference_time.utcoffset() != local_time.utcoffset() or not self.referenceTime.endswith("+09:00"):
            raise PydanticCustomError("time_zone_offset_mismatch", "기준 시각 오프셋이 업무 시간대와 일치해야 합니다.")
        if "T" not in self.referenceTime:
            raise ValueError("기준 시각은 대문자 T를 사용해야 합니다.")
        return self


class ReceptionDraftFields(TypedDict, total=False):
    __pydantic_config__ = ConfigDict(extra="forbid", strict=True)

    workType: WorkType
    symptoms: Symptoms
    address: Address
    desiredVisitFrom: DateTime
    desiredVisitTo: DateTime
    notes: Notes


class AnalyzeResponse(BaseModel):
    model_config = ConfigDict(extra="forbid", strict=True)

    analysisRequestId: Id
    recordingFileId: Id
    transcriptFileId: Id
    receptionId: Id
    receptionVersion: Annotated[int, Field(ge=1)]
    status: Literal["SUCCEEDED"]
    aiOriginal: ReceptionDraftFields
    missingFields: Annotated[list[RequiredDraftField], Field(json_schema_extra={"uniqueItems": True})]


class FieldError(TypedDict):
    __pydantic_config__ = ConfigDict(extra="forbid")

    field: NonEmptyString
    message: NonEmptyString


class ErrorResponse(TypedDict):
    __pydantic_config__ = ConfigDict(extra="forbid")

    code: NonEmptyString
    message: NonEmptyString
    traceId: NonEmptyString
    resourceId: NotRequired[str | None]
    currentState: NotRequired[dict[str, Any]]
    fieldErrors: NotRequired[list[FieldError]]
