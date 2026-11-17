package com.allcheck.reception.presentation.dto.response.review

import com.allcheck.reception.domain.vo.review.ReceptionData
import com.allcheck.reception.domain.vo.review.WorkType
import com.fasterxml.jackson.annotation.JsonInclude
import io.swagger.v3.oas.annotations.media.Schema
import java.time.OffsetDateTime

@Schema(name = "ReceptionReviewedFields", description = "관리자가 검토 완료로 저장한 전체 최종값")
data class ReceptionFieldsResponse(
    @field:Schema(description = "작업 유형")
    val workType: WorkType,
    @field:Schema(description = "고객이 전달한 증상")
    val symptoms: String,
    @field:Schema(description = "방문 주소")
    val address: String,
    @field:Schema(description = "정확한 방문 희망 시작 시각")
    val desiredVisitFrom: OffsetDateTime,
    @field:Schema(description = "정확한 방문 희망 종료 시각")
    val desiredVisitTo: OffsetDateTime,
    @field:Schema(description = "관리자가 확인한 고객 연락처")
    val contactPhone: String,
    @get:JsonInclude(JsonInclude.Include.NON_NULL)
    @field:Schema(description = "관리자가 확인한 부가정보. 값이 없으면 필드를 생략한다.")
    val notes: String?,
) {
    companion object {
        /** 확정 또는 확정 취소 응답에만 사용한다. 불완전한 AI 초안의 상세 응답과 구분한다. */
        fun from(data: ReceptionData) = ReceptionFieldsResponse(
            workType = checkNotNull(data.workType),
            symptoms = checkNotNull(data.symptoms),
            address = checkNotNull(data.address),
            desiredVisitFrom = checkNotNull(data.desiredVisitFrom),
            desiredVisitTo = checkNotNull(data.desiredVisitTo),
            contactPhone = checkNotNull(data.contactPhone),
            notes = data.notes,
        )
    }
}
