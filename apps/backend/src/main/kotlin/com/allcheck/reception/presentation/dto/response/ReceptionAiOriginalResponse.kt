package com.allcheck.reception.presentation.dto.response

import com.allcheck.reception.domain.vo.ReceptionAiOriginal
import com.allcheck.reception.domain.vo.WorkType
import com.fasterxml.jackson.annotation.JsonInclude
import io.swagger.v3.oas.annotations.media.Schema
import java.time.OffsetDateTime

@JsonInclude(JsonInclude.Include.NON_NULL)
@Schema(name = "ReceptionDraftFields")
data class ReceptionAiOriginalResponse(
    @field:Schema(description = "작업 유형")
    val workType: WorkType?,
    @field:Schema(description = "고객 증상")
    val symptoms: String?,
    @field:Schema(description = "방문 주소")
    val address: String?,
    @field:Schema(description = "정확한 방문 희망 시작 시각")
    val desiredVisitFrom: OffsetDateTime?,
    @field:Schema(description = "정확한 방문 희망 종료 시각")
    val desiredVisitTo: OffsetDateTime?,
    @field:Schema(description = "고객 요청 등 부가정보")
    val notes: String?,
) {
    companion object {
        fun from(data: ReceptionAiOriginal) = ReceptionAiOriginalResponse(
            workType = data.workType,
            symptoms = data.symptoms,
            address = data.address,
            desiredVisitFrom = data.desiredVisitFrom,
            desiredVisitTo = data.desiredVisitTo,
            notes = data.notes,
        )
    }
}
