package com.allcheck.operations

import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate

/** 운영 원본에서 관리자 통합 조회에 필요한 데이터만 읽는다. */
interface DashboardOperations {
    fun requestPage(filter: DashboardFilter, offset: Int, limit: Int): List<DashboardRequest>
    fun statistics(filter: DashboardFilter, weekAnchor: LocalDate): List<DashboardStatisticsGroup>
}

data class DashboardFilter(
    val from: Instant?,
    val until: Instant?,
    val requestStatus: RequestStatus?,
    val workStatus: WorkStatus?,
    val workType: WorkType?,
    val driverId: Long?,
    val region: String?,
    val needsAdminAction: Boolean?,
    val query: String?,
)

data class DashboardRequest(
    val requestId: Long,
    val receptionId: Long,
    val status: RequestStatus,
    val version: Int,
    val intake: RequestIntake,
    val area: String,
    val currentRoundId: Long?,
    val currentRoundStatus: RoundStatus?,
    val currentWorkId: Long?,
    val currentWorkStatus: WorkStatus?,
    val currentWorkDriverId: Long?,
    val followUpTarget: FollowUpTarget?,
    val followUpReason: String?,
    val createdAt: Instant,
)

data class DashboardStatisticsGroup(
    val weekStart: LocalDate,
    val workType: WorkType,
    val status: RequestStatus,
    val requestCount: Long,
    val reportCount: Long,
    val totalCost: BigDecimal,
)
