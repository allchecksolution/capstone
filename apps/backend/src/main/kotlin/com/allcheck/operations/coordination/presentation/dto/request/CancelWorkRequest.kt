package com.allcheck.operations.coordination.presentation.dto.request

import com.allcheck.operations.CancellationReason
import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotBlank

data class CancelWorkRequest(
    @field:Schema(description = "현재 작업 버전") @field:Min(1) val expectedVersion: Int,
    @field:Schema(description = "취소 사유 분류") val reasonCode: CancellationReason,
    @field:Schema(description = "모든 사유에서 필수. 최대 1000 유니코드 코드포인트") @field:NotBlank val reasonDetail: String,
) : StrictOperationsRequest()
