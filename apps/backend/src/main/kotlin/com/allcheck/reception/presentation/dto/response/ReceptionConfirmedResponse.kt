package com.allcheck.reception.presentation.dto.response

import com.allcheck.reception.domain.vo.ReceptionReviewStatus
import io.swagger.v3.oas.annotations.media.Schema
import java.time.Instant

@Schema(name = "ConfirmedReception", description = "검토 완료로 저장된 접수")
data class ReceptionConfirmedResponse(
    @field:Schema(description = "접수 식별자")
    val receptionId: String,
    @field:Schema(description = "검토 상태", allowableValues = ["CONFIRMED"])
    val reviewStatus: ReceptionReviewStatus,
    @field:Schema(description = "검토 완료로 증가한 업무 버전")
    val version: Int,
    @field:Schema(description = "서버가 기록한 검토 완료 시각")
    val confirmedAt: Instant,
    @field:Schema(description = "저장된 전체 최종값")
    val finalData: ReceptionFieldsResponse,
)
