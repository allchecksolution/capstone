package com.allcheck.dashboard.presentation.dto.response

import java.math.BigDecimal

data class DashboardMetrics(
    val totalRequests: Long,
    val openRequests: Long,
    val completedRequests: Long,
    val cancelledRequests: Long,
    val completionRate: Double,
    val cancellationRate: Double,
    val totalCostKrw: BigDecimal,
    val averageCostKrw: BigDecimal?,
    val weeklyCounts: List<WeeklyRequestCount>,
    val workTypes: List<RequestTypeCount>,
)
