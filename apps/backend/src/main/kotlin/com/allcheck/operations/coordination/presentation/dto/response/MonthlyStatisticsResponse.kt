package com.allcheck.operations.coordination.presentation.dto.response

import io.swagger.v3.oas.annotations.media.Schema

data class MonthlyStatisticsResponse(
    @field:Schema(description = "Asia/Seoul 기준 YYYY-MM") val month: String,
    @field:Schema(description = "배정 시각 기준 배정 수") val assignments: Long,
    @field:Schema(description = "최초 보고서 저장 시각 기준 완료 수") val completed: Long,
    @field:Schema(description = "작업 취소 시각 기준 취소 수") val cancelled: Long,
    @field:Schema(description = "다섯 작업 유형별 완료 분포") val workTypes: List<WorkTypeDistribution>,
)
