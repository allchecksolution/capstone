package com.allcheck.reception.presentation.dto.response.review

import com.allcheck.reception.domain.vo.review.ReceptionData
import com.allcheck.reception.domain.vo.review.WorkType
import com.fasterxml.jackson.annotation.JsonInclude
import io.swagger.v3.oas.annotations.media.Schema
import java.time.OffsetDateTime

@JsonInclude(JsonInclude.Include.NON_NULL)
@Schema(name = "ReceptionEditableFields")
data class ReceptionEditableResponse(
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
    @field:Schema(description = "관리자가 확인한 고객 연락처")
    val contactPhone: String?,
    @field:Schema(description = "고객 요청 등 부가정보")
    val notes: String?,
) {
    companion object {
        fun from(data: ReceptionData) = ReceptionEditableResponse(
            workType = data.workType,
            symptoms = data.symptoms,
            address = data.address,
            desiredVisitFrom = data.desiredVisitFrom,
            desiredVisitTo = data.desiredVisitTo,
            contactPhone = data.contactPhone,
            notes = data.notes,
        )
    }
}
