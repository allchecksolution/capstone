package com.allcheck.operations.coordination.presentation.dto.request

import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.constraints.Min
import java.time.Instant

data class RevisitPlanRequest(
    @field:Schema(description = "현재 작업 버전") @field:Min(1) val expectedVersion: Int,
    @field:Schema(description = "예정 재방문 시각. null이면 미정으로 변경") val plannedRevisitAt: Instant? = null,
) : StrictOperationsRequest()
