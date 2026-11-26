package com.allcheck.operations.work

import com.allcheck.operations.CancellationFact
import com.allcheck.operations.CancellationReason
import com.allcheck.operations.WorkEvent
import com.allcheck.operations.WorkStatus
import java.time.Instant

interface WorkOperations {
    fun create(requestId: Long, roundId: Long, offerId: Long, driverId: Long, previousWorkId: Long?, now: Instant): WorkSnapshot
    fun get(workId: Long): WorkSnapshot
    fun forRequest(requestId: Long): List<WorkSnapshot>
    fun occupied(driverId: Long): Boolean
    fun occupiedDrivers(driverIds: Set<Long>): Set<Long>
    fun excluded(requestId: Long): Set<Long>
    fun transition(workId: Long, driverId: Long, version: Int, target: WorkStatus, reason: String?, planned: Instant?, now: Instant): WorkSnapshot
    fun planRevisit(workId: Long, driverId: Long, version: Int, planned: Instant?, now: Instant): WorkSnapshot
    fun cancel(workId: Long, driverId: Long, version: Int, code: CancellationReason, detail: String, now: Instant): WorkSnapshot
    fun complete(workId: Long, driverId: Long, version: Int, now: Instant): WorkSnapshot
    fun driverWorks(driverId: Long, history: Boolean, from: Instant?, to: Instant?, page: Int, size: Int): List<WorkSnapshot>
    fun statistics(driverId: Long, from: Instant, to: Instant): MonthlyFacts
    fun cancelled(page: Int, size: Int): List<WorkSnapshot>
    fun cancellation(cancellationId: Long): WorkSnapshot
}
data class WorkSnapshot(val workId: Long, val requestId: Long, val roundId: Long, val offerId: Long, val driverId: Long, val previousWorkId: Long?, val status: WorkStatus, val version: Int, val assignedAt: Instant, val completedAt: Instant?, val cancelledAt: Instant?, val events: List<WorkEvent>, val cancellation: CancellationFact?) {
    fun unfinished() = status !in setOf(WorkStatus.WORK_DONE, WorkStatus.CANCELLED)
}
data class MonthlyFacts(val assignments: Long, val completed: Long, val cancelled: Long, val completedWorkTypes: Map<String, Long>)
