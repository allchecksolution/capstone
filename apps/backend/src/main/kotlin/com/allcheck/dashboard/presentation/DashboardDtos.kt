package com.allcheck.dashboard.presentation

import com.allcheck.operations.RequestStatus
import com.allcheck.operations.RoundStatus
import com.allcheck.operations.WorkStatus
import com.allcheck.operations.WorkType
import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.constraints.Positive
import jakarta.validation.constraints.Size
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime

data class DashboardParameters(
    @field:Schema(description = "기존 의뢰 상태 필터. requestStatus와 같이 쓰면 값이 같아야 한다.") val status: RequestStatus? = null,
    val requestStatus: RequestStatus? = null,
    val workStatus: WorkStatus? = null,
    val workType: WorkType? = null,
    @field:Positive val driverId: Long? = null,
    @field:Size(min = 1, max = 100) val region: String? = null,
    val needsAdminAction: Boolean? = null,
    @field:Size(min = 1, max = 100) val q: String? = null,
)

data class DashboardPage(val items: List<DashboardRequestItem>, val page: Int, val size: Int, val hasNext: Boolean)

data class DashboardFollowUpTarget(val cancellationId: String?, val dispatchRoundId: String?)

data class DashboardRequestItem(
    val requestId: String,
    val receptionId: String,
    val requestStatus: RequestStatus,
    val version: Int,
    val workType: WorkType,
    val symptoms: String,
    val address: String,
    val desiredVisitFrom: OffsetDateTime,
    val desiredVisitTo: OffsetDateTime,
    val contactPhone: String,
    val notes: String?,
    val currentDispatchRoundId: String?,
    val currentWorkId: String?,
    val followUpTarget: DashboardFollowUpTarget?,
    val followUpReason: String?,
    val createdAt: Instant,
    val area: String,
    val currentDispatchStatus: RoundStatus?,
    val currentWorkStatus: WorkStatus?,
    val currentDriverId: Long?,
    val needsAdminAction: Boolean,
)

data class StatisticsPeriod(val from: LocalDate, val to: LocalDate)

data class WeeklyRequestCount(val from: LocalDate, val to: LocalDate, val count: Long)

data class RequestTypeCount(val workType: WorkType, val count: Long, val ratio: Double)

data class DashboardStatistics(
    val period: StatisticsPeriod,
    val hasData: Boolean,
    val metrics: DashboardMetrics?,
)

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
