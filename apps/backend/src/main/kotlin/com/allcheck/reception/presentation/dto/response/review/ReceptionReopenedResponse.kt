package com.allcheck.reception.presentation.dto.response.review

import com.allcheck.reception.domain.vo.review.ReceptionReviewStatus
import io.swagger.v3.oas.annotations.media.Schema
import java.time.Instant

@Schema(name = "ReceptionReopened", description = "확정을 취소하고 기존 편집값을 보존한 접수")
data class ReceptionReopenedResponse(
    @field:Schema(description = "접수 식별자")
    val receptionId: String,
    @field:Schema(description = "검토 상태", allowableValues = ["DRAFT"])
    val reviewStatus: ReceptionReviewStatus,
    @field:Schema(description = "확정 취소로 증가한 업무 버전")
    val version: Int,
    @field:Schema(description = "서버가 기록한 확정 취소 시각")
    val updatedAt: Instant,
    @field:Schema(description = "확정 취소로 해제된 검토 완료 시각", nullable = true)
    val confirmedAt: Instant?,
    @field:Schema(description = "확정 취소 후에도 보존된 전체 편집값")
    val finalData: ReceptionFieldsResponse,
)
