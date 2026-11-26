package com.allcheck.operations.coordination.presentation.dto.response

import com.allcheck.operations.CancellationReason
import com.allcheck.operations.WorkStatus
import io.swagger.v3.oas.annotations.media.Schema
import java.time.Instant

data class CancellationResponse(
    @field:Schema(description = "취소 식별자") val cancellationId: String,
    @field:Schema(description = "작업 식별자") val workId: String,
    @field:Schema(description = "의뢰 식별자") val requestId: String,
    @field:Schema(description = "사유 분류") val reasonCode: CancellationReason,
    @field:Schema(description = "필수 상세 사유") val reasonDetail: String,
    @field:Schema(description = "취소 직전 작업 상태") val workStatusBeforeCancellation: WorkStatus,
    @field:Schema(description = "실제 취소 시각") val cancelledAt: Instant,
)
