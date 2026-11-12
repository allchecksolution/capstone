import re
from datetime import datetime
from typing import Annotated, Any, Literal, NotRequired, TypedDict
from zoneinfo import ZoneInfo, ZoneInfoNotFoundError

from pydantic import (
    AfterValidator,
    BaseModel,
    ConfigDict,
    Field,
    StringConstraints,
    WithJsonSchema,
    field_validator,
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
WorkType = Literal["EXTERIOR_LEAK", "INTERIOR_LEAK", "BLOCKAGE"]
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

    jobId: Id
    receptionId: Id
    receptionVersion: Annotated[int, Field(ge=1)]
    transcript: Annotated[str, StringConstraints(max_length=100_000)]
    referenceTime: DateTime
    timeZone: Annotated[str, StringConstraints(min_length=1)]

    @field_validator("timeZone")
    @classmethod
    def validate_time_zone(cls, value: str) -> str:
        try:
            ZoneInfo(value)
        except (ZoneInfoNotFoundError, ValueError) as error:
            raise ValueError("IANA 시간대 이름이 필요합니다.") from error
        return value


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

    jobId: Id
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
