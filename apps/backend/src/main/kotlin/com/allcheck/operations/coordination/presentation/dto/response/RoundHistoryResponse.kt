package com.allcheck.operations.coordination.presentation.dto.response

import com.allcheck.operations.RoundStatus
import io.swagger.v3.oas.annotations.media.Schema
import java.time.Instant

data class RoundHistoryResponse(
    @field:Schema(description = "회차 식별자") val dispatchRoundId: String,
    @field:Schema(description = "회차 최종 또는 현재 상태") val status: RoundStatus,
    @field:Schema(description = "검색 반경 킬로미터") val radiusKm: Int,
    @field:Schema(description = "회차 시작") val createdAt: Instant,
    @field:Schema(description = "회차 종료") val closedAt: Instant?,
    @field:Schema(description = "기사별 제안과 응답") val offers: List<OfferHistoryResponse>,
    @field:Schema(description = "배정 작업 및 취소 상세") val work: WorkResponse?,
)
