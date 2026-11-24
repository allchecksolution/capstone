package com.allcheck.dashboard.presentation.dto.response

import io.swagger.v3.oas.annotations.media.Schema

data class DashboardStatistics(
    @field:Schema(description = "한국시간 의뢰 등록일 기준 조회 기간. 시작일과 종료일을 포함한다.")
    val period: StatisticsPeriod,
    @field:Schema(description = "기간과 필터가 일치하는 의뢰가 있는지 여부")
    val hasData: Boolean,
    @field:Schema(description = "기간과 필터가 일치하는 의뢰의 통계. hasData가 false이면 null.")
    val metrics: DashboardMetrics?,
)
