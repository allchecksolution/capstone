package com.allcheck.operations.coordination.presentation.dto.request

import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotBlank

data class RegisterServiceRequest(
    @field:Schema(description = "검토 완료된 본인 접수") @field:NotBlank val receptionId: String,
    @field:Schema(description = "접수 확정 버전") @field:Min(1) val expectedVersion: Int,
) : StrictOperationsRequest()
