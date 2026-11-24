package com.allcheck.operations.coordination.presentation.dto.response

import com.allcheck.operations.WorkStatus
import io.swagger.v3.oas.annotations.media.Schema
import java.time.Instant

data class WorkEventResponse(
    @field:Schema(description = "기록된 상태") val status: WorkStatus,
    @field:Schema(description = "기록 시각") val at: Instant,
    @field:Schema(description = "재방문 사유") val reason: String?,
    @field:Schema(description = "예정 재방문 시각. 미정이면 null") val plannedRevisitAt: Instant?,
)
