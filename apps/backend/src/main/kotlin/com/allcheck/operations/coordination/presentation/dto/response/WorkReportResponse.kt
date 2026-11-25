package com.allcheck.operations.coordination.presentation.dto.response

import com.allcheck.operations.ReportContent
import io.swagger.v3.oas.annotations.media.Schema
import java.time.Instant

data class WorkReportResponse(
    @field:Schema(description = "보고서 식별자") val reportId: String,
    @field:Schema(description = "작업 식별자") val workId: String,
    @field:Schema(description = "최초 제출 내용") val original: ReportContent,
    @field:Schema(description = "최신 유효 내용") val latest: ReportContent,
    @field:Schema(description = "보존된 작업 전 사진") val beforePhotoIds: List<String>,
    @field:Schema(description = "보존된 작업 후 사진") val afterPhotoIds: List<String>,
    @field:Schema(description = "현재 보고서 버전") val version: Int,
    @field:Schema(description = "불변 최초 제출 시각") val submittedAt: Instant,
    @field:Schema(description = "관리자 수정 이력") val revisions: List<ReportRevisionResponse>,
)
