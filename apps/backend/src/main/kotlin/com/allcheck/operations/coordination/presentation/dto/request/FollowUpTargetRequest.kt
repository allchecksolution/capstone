package com.allcheck.operations.coordination.presentation.dto.request

import com.allcheck.operations.FollowUpTarget
import com.allcheck.operations.coordination.presentation.OperationsId
import com.allcheck.operations.validInput
import io.swagger.v3.oas.annotations.media.Schema

data class FollowUpTargetRequest(
    @field:Schema(description = "취소 대상. 회차 대상과 양자택일") val cancellationId: String? = null,
    @field:Schema(description = "배차 회차 대상. 취소 대상과 양자택일") val dispatchRoundId: String? = null,
) : StrictOperationsRequest() {
    fun target(): FollowUpTarget {
        validInput((cancellationId == null) != (dispatchRoundId == null))
        return if (cancellationId != null) FollowUpTarget("CANCELLATION", OperationsId.CANCELLATION.parse(cancellationId)) else FollowUpTarget("ROUND", OperationsId.ROUND.parse(checkNotNull(dispatchRoundId)))
    }
}
