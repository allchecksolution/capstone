package com.allcheck.operations.coordination.presentation.dto.response

import io.swagger.v3.oas.annotations.media.Schema

data class FollowUpTargetResponse(
    @field:Schema(description = "작업 취소 대상. 회차 대상이면 null") val cancellationId: String?,
    @field:Schema(description = "배차 회차 대상. 작업 취소 대상이면 null") val dispatchRoundId: String?,
)
