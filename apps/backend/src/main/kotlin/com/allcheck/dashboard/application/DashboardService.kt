package com.allcheck.dashboard.application

import com.allcheck.dashboard.presentation.dto.response.DashboardFollowUpTarget
import com.allcheck.dashboard.presentation.dto.response.DashboardMetrics
import com.allcheck.dashboard.presentation.dto.response.DashboardPage
import com.allcheck.dashboard.presentation.dto.response.DashboardRequestItem
import com.allcheck.dashboard.presentation.dto.response.DashboardStatistics
import com.allcheck.dashboard.presentation.dto.response.RequestTypeCount
import com.allcheck.dashboard.presentation.dto.response.StatisticsPeriod
import com.allcheck.dashboard.presentation.dto.response.WeeklyRequestCount
import com.allcheck.global.exception.BusinessException
import com.allcheck.global.exception.GlobalErrorCode
import com.allcheck.operations.DashboardFilter
import com.allcheck.operations.DashboardOperations
import com.allcheck.operations.DashboardRequest
import com.allcheck.operations.RequestStatus
import com.allcheck.operations.WorkType
import com.allcheck.operations.validInput
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.ZoneId

@Service
@Transactional(readOnly = true)
internal class DashboardService(private val operations: DashboardOperations) {

    fun requests(criteria: DashboardCriteria, page: Int, size: Int): DashboardPage {
        val rows = operations.requestPage(criteria.toFilter(), (page - 1) * size, size + 1)
        return DashboardPage(rows.take(size).map { it.toItem() }, page, size, rows.size > size)
    }

    fun statistics(criteria: DashboardCriteria): DashboardStatistics {
        if (criteria.from == null || criteria.to == null) throw BusinessException(GlobalErrorCode.INVALID_INPUT_VALUE)
        val query = criteria.toFilter()
        val period = StatisticsPeriod(criteria.from, criteria.to)
        val groups = operations.statistics(query, period.from)
        val metrics = if (groups.isEmpty()) {
            null
        } else {
            val total = groups.sumOf { it.requestCount }
            val completed = groups.filter { it.status == RequestStatus.COMPLETED }.sumOf { it.requestCount }
            val cancelled = groups.filter { it.status == RequestStatus.CANCELLED }.sumOf { it.requestCount }
            val cost = groups.sumOf { it.totalCost }
            val reports = groups.sumOf { it.reportCount }
            val byType = groups.groupingBy { it.workType }.fold(0L) { count, group -> count + group.requestCount }
            DashboardMetrics(
                totalRequests = total,
                openRequests = total - completed - cancelled,
                completedRequests = completed,
                cancelledRequests = cancelled,
                completionRate = completed.toDouble() / total,
                cancellationRate = cancelled.toDouble() / total,
                totalCostKrw = cost,
                averageCostKrw = if (reports == 0L) null else cost.divide(BigDecimal.valueOf(reports), 0, RoundingMode.HALF_UP),
                weeklyCounts = groups.groupBy { it.weekStart }.toSortedMap().map { (week, rows) ->
                    WeeklyRequestCount(week, minOf(week.plusDays(6), period.to), rows.sumOf { it.requestCount })
                },
                workTypes = WorkType.entries.map { type ->
                    val count = byType[type] ?: 0L
                    RequestTypeCount(type, count, count.toDouble() / total)
                },
            )
        }
        return DashboardStatistics(period, metrics != null, metrics)
    }

    private fun DashboardCriteria.toFilter(): DashboardFilter {
        validInput(from == null || from.year in 1..9999)
        validInput(to == null || to.year in 1..9999)
        validInput(from == null || to == null || from <= to)
        validInput(region?.isNotBlank() != false && query?.isNotBlank() != false)
        return DashboardFilter(
            from = from?.atStartOfDay(SEOUL)?.toInstant(),
            until = to?.plusDays(1)?.atStartOfDay(SEOUL)?.toInstant(),
            requestStatus = requestStatus,
            workStatus = workStatus,
            workType = workType,
            driverId = driverId,
            region = region?.trim(),
            needsAdminAction = needsAdminAction,
            query = query?.trim(),
        )
    }

    private fun DashboardRequest.toItem() = DashboardRequestItem(
        requestId = "req_$requestId",
        receptionId = "rec_$receptionId",
        requestStatus = status,
        version = version,
        workType = intake.workType,
        symptoms = intake.symptoms,
        address = intake.address,
        desiredVisitFrom = intake.desiredVisitFrom,
        desiredVisitTo = intake.desiredVisitTo,
        contactPhone = intake.contactPhone,
        notes = intake.notes,
        currentDispatchRoundId = currentRoundId?.let { "round_$it" },
        currentWorkId = currentWorkId?.let { "work_$it" },
        followUpTarget = followUpTarget?.let {
            DashboardFollowUpTarget(
                cancellationId = if (it.kind == "CANCELLATION") "cancel_${it.id}" else null,
                dispatchRoundId = if (it.kind == "ROUND") "round_${it.id}" else null,
            )
        },
        followUpReason = followUpReason,
        createdAt = createdAt,
        area = area,
        currentDispatchStatus = currentRoundStatus,
        currentWorkStatus = currentWorkStatus,
        currentDriverId = currentWorkDriverId,
        needsAdminAction = status == RequestStatus.FOLLOW_UP_REQUIRED,
    )

    companion object {
        private val SEOUL = ZoneId.of("Asia/Seoul")
    }
}
