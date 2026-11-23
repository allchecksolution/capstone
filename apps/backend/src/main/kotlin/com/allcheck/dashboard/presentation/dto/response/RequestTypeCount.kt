package com.allcheck.dashboard.presentation.dto.response

import com.allcheck.operations.WorkType
import io.swagger.v3.oas.annotations.media.Schema

data class RequestTypeCount(
    val workType: WorkType,
    val count: Long,
    @field:Schema(description = "전체 일치 의뢰 중 이 작업 유형의 비율", minimum = "0", maximum = "1")
    val ratio: Double,
)
