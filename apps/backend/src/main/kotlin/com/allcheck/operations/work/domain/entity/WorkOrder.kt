package com.allcheck.operations.work.domain.entity

import com.allcheck.global.exception.BusinessException
import com.allcheck.operations.WorkEvent
import com.allcheck.operations.WorkStatus
import com.allcheck.operations.exception.OperationsErrorCode
import com.allcheck.operations.expectedVersion
import com.allcheck.operations.validInput
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import jakarta.persistence.Version
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes
import java.time.Instant

@Entity
@Table(name = "work_orders")
class WorkOrder(
    @field:Column(nullable = false, updatable = false) val requestId: Long,
    @field:Column(nullable = false, unique = true, updatable = false) val roundId: Long,
    @field:Column(nullable = false, unique = true, updatable = false) val offerId: Long,
    @field:Column(nullable = false, updatable = false) val driverId: Long,
    @field:Column(updatable = false) val previousWorkId: Long?,
    @field:Column(nullable = false, updatable = false) val assignedAt: Instant,
) {
    @field:Id
    @field:GeneratedValue(strategy = GenerationType.IDENTITY)
    @field:Column(nullable = false, updatable = false)
    var workId: Long? = null
        protected set

    @field:Enumerated(EnumType.STRING)
    @field:Column(nullable = false, length = 30)
    var status: WorkStatus = WorkStatus.NOT_STARTED
        protected set

    @field:Column(nullable = false)
    var version: Int = 1
        protected set

    @field:Version
    @field:Column(nullable = false)
    var persistenceVersion: Long = 0
        protected set

    @field:Column
    var completedAt: Instant? = null
        protected set

    @field:Column
    var cancelledAt: Instant? = null
        protected set

    @field:Column(nullable = false)
    var updatedAt: Instant = assignedAt
        protected set

    @field:JdbcTypeCode(SqlTypes.JSON)
    @field:Column(name = "events", nullable = false, columnDefinition = "jsonb")
    private var storedEvents: List<WorkEvent> = listOf(WorkEvent(WorkStatus.NOT_STARTED, assignedAt))

    fun events(): List<WorkEvent> = storedEvents.toList()
    fun unfinished(): Boolean = status !in setOf(WorkStatus.WORK_DONE, WorkStatus.CANCELLED)

    fun transition(expected: Int, target: WorkStatus, reason: String?, plannedRevisitAt: Instant?, now: Instant) {
        expectedVersion(version, expected)
        val allowed = when (status) {
            WorkStatus.NOT_STARTED, WorkStatus.REVISIT_REQUIRED -> target == WorkStatus.EN_ROUTE
            WorkStatus.EN_ROUTE -> target == WorkStatus.ARRIVED
            WorkStatus.ARRIVED -> target == WorkStatus.IN_PROGRESS
            WorkStatus.IN_PROGRESS -> target == WorkStatus.REVISIT_REQUIRED
            else -> false
        }
        state(allowed)
        if (target == WorkStatus.REVISIT_REQUIRED) {
            validInput(!reason.isNullOrBlank())
        } else {
            validInput(reason == null && plannedRevisitAt == null)
        }
        change(target, now, reason, plannedRevisitAt)
    }

    fun planRevisit(expected: Int, planned: Instant?, now: Instant) {
        expectedVersion(version, expected)
        state(status == WorkStatus.REVISIT_REQUIRED)
        change(status, now, storedEvents.last { it.status == WorkStatus.REVISIT_REQUIRED }.reason, planned)
    }

    fun cancel(expected: Int, now: Instant) {
        expectedVersion(version, expected)
        state(unfinished())
        cancelledAt = now
        change(WorkStatus.CANCELLED, now)
    }

    fun complete(expected: Int, now: Instant) {
        expectedVersion(version, expected)
        state(status == WorkStatus.IN_PROGRESS)
        completedAt = now
        change(WorkStatus.WORK_DONE, now)
    }

    private fun change(target: WorkStatus, now: Instant, reason: String? = null, planned: Instant? = null) {
        status = target
        updatedAt = now
        version++
        storedEvents = storedEvents + WorkEvent(target, now, reason, planned)
    }
    private fun state(value: Boolean) {
        if (!value) throw BusinessException(OperationsErrorCode.OPERATIONS_STATE_CONFLICT)
    }
}
