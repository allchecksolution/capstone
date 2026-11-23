package com.allcheck.operations.coordination.presentation.dto.response

import com.allcheck.operations.FollowUpChoice
import io.swagger.v3.oas.annotations.media.Schema
import java.time.Instant

data class DecisionResponse(
    @field:Schema(description = "결정 식별자") val decisionId: String,
    @field:Schema(description = "결정한 관리자") val adminId: String,
    @field:Schema(description = "결정 대상") val target: FollowUpTargetResponse,
    @field:Schema(description = "결정 내용") val decision: FollowUpChoice,
    @field:Schema(description = "새 회차. 의뢰 취소이면 null") val dispatchRoundId: String?,
    @field:Schema(description = "결정 시각") val decidedAt: Instant,
)
