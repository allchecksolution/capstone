package com.allcheck.operations.coordination.presentation.dto.response

import io.swagger.v3.oas.annotations.media.Schema

data class OperationsPage<T>(
    @field:Schema(description = "현재 페이지 항목") val items: List<T>,
    @field:Schema(description = "1부터 시작하는 페이지") val page: Int,
    @field:Schema(description = "요청한 페이지 크기") val size: Int,
    @field:Schema(description = "다음 페이지 존재 여부") val hasNext: Boolean,
)
