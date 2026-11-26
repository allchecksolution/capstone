package com.allcheck.operations.coordination.presentation.dto.response

import com.allcheck.operations.ReportContent
import io.swagger.v3.oas.annotations.media.Schema
import java.time.Instant

data class ReportRevisionResponse(
    @field:Schema(description = "수정 식별자") val revisionId: String,
    @field:Schema(description = "수정 후 보고서 버전") val version: Int,
    @field:Schema(description = "수정 관리자") val adminId: String,
    @field:Schema(description = "필수 수정 사유") val reason: String,
    @field:Schema(description = "수정 전 내용") val before: ReportContent,
    @field:Schema(description = "수정 후 내용") val after: ReportContent,
    @field:Schema(description = "수정 시각") val revisedAt: Instant,
)
