package com.allcheck.operations.coordination.presentation.dto.request

import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.Valid
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotBlank

data class ReviseReportRequest(
    @field:Schema(description = "현재 보고서 버전") @field:Min(1) val expectedVersion: Int,
    @field:Schema(description = "필수 수정 사유") @field:NotBlank val reason: String,
    @field:Schema(description = "허용된 내용 필드 변경") @field:Valid val changes: ReportChangesRequest,
) : StrictOperationsRequest()
