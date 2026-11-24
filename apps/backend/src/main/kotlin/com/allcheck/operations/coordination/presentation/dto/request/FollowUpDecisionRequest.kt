package com.allcheck.operations.coordination.presentation.dto.request

import com.allcheck.operations.FollowUpChoice
import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.Valid
import jakarta.validation.constraints.Min

data class FollowUpDecisionRequest(
    @field:Schema(description = "현재 의뢰 버전") @field:Min(1) val expectedVersion: Int,
    @field:Schema(description = "현재 미해결 대상") @field:Valid val target: FollowUpTargetRequest,
    @field:Schema(description = "재배차 또는 의뢰 취소") val decision: FollowUpChoice,
) : StrictOperationsRequest()
