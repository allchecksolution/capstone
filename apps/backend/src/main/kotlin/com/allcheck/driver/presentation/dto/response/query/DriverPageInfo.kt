package com.allcheck.driver.presentation.dto.response.query

import io.swagger.v3.oas.annotations.media.Schema

data class DriverPageInfo(
    @field:Schema(description = "1부터 시작하는 페이지") val page: Int,
    @field:Schema(description = "페이지 크기") val size: Int,
    @field:Schema(description = "전체 기사 수") val totalElements: Long,
    @field:Schema(description = "전체 페이지 수") val totalPages: Int,
)
