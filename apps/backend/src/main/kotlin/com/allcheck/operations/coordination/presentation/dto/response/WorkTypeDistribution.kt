package com.allcheck.operations.coordination.presentation.dto.response

import com.allcheck.operations.WorkType
import io.swagger.v3.oas.annotations.media.Schema

data class WorkTypeDistribution(
    @field:Schema(description = "작업 유형") val workType: WorkType,
    @field:Schema(description = "해당 월 완료 작업 수") val count: Long,
    @field:Schema(description = "해당 월 완료 합계 대비 비율. 완료가 없으면 0") val ratio: Double,
)
