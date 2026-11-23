package com.allcheck.operations.coordination.presentation.dto.request

import com.allcheck.operations.WorkStatus
import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.constraints.Min
import java.time.Instant

data class TransitionWorkRequest(
    @field:Schema(description = "현재 작업 버전") @field:Min(1) val expectedVersion: Int,
    @field:Schema(description = "진행 또는 재방문 대상 상태. 완료·취소는 전용 API") val targetStatus: WorkStatus,
    @field:Schema(description = "재방문 전환 시 필수 사유") val reason: String? = null,
    @field:Schema(description = "예정 재방문 시각. 미정 허용") val plannedRevisitAt: Instant? = null,
) : StrictOperationsRequest()
