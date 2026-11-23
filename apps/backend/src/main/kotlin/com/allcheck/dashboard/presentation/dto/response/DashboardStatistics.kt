package com.allcheck.dashboard.presentation.dto.response

data class DashboardStatistics(
    val period: StatisticsPeriod,
    val hasData: Boolean,
    val metrics: DashboardMetrics?,
)
