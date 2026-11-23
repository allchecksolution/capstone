package com.allcheck.operations.coordination.presentation.dto.response

import io.swagger.v3.oas.annotations.media.Schema

data class DispatchHistoryResponse(
    @field:Schema(description = "의뢰 현재 상세") val request: ServiceRequestResponse,
    @field:Schema(description = "최초 배차부터 모든 재배차 회차") val rounds: List<RoundHistoryResponse>,
    @field:Schema(description = "모든 후속 결정 이력") val followUpDecisions: List<DecisionResponse>,
)
