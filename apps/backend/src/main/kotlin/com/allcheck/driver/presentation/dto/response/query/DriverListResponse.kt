package com.allcheck.driver.presentation.dto.response.query

import io.swagger.v3.oas.annotations.media.Schema

data class DriverListResponse(
    @field:Schema(description = "기사 목록") val items: List<DriverSummary>,
    @field:Schema(description = "페이지 정보") val page: DriverPageInfo,
)
