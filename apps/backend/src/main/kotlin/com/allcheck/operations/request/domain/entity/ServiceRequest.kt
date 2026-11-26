package com.allcheck.operations.request.domain.entity

import com.allcheck.global.exception.BusinessException
import com.allcheck.operations.FollowUpChoice
import com.allcheck.operations.FollowUpTarget
import com.allcheck.operations.RequestIntake
import com.allcheck.operations.RequestStatus
import com.allcheck.operations.SiteCoordinates
import com.allcheck.operations.exception.OperationsErrorCode
import com.allcheck.operations.expectedVersion
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import jakarta.persistence.Version
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes
import java.time.Instant

@Entity
@Table(name = "service_requests")
class ServiceRequest(
    @field:Column(nullable = false, unique = true, updatable = false) val receptionId: Long,
    @field:Column(nullable = false, updatable = false) val createdByAdminId: Long,
    @field:JdbcTypeCode(SqlTypes.JSON)
    @field:Column(nullable = false, columnDefinition = "jsonb", updatable = false) val intake: RequestIntake,
    @field:JdbcTypeCode(SqlTypes.JSON)
    @field:Column(nullable = false, columnDefinition = "jsonb", updatable = false) val site: SiteCoordinates,
    @field:Column(nullable = false, updatable = false) val createdAt: Instant,
) {
    @field:Id
    @field:GeneratedValue(strategy = GenerationType.IDENTITY)
    @field:Column(nullable = false, updatable = false)
    var requestId: Long? = null
        protected set

    @field:Column(nullable = false, length = 30)
    @field:jakarta.persistence.Enumerated(jakarta.persistence.EnumType.STRING)
    var status: RequestStatus = RequestStatus.OPEN
        protected set

    @field:Column(nullable = false)
    var version: Int = 1
        protected set

    @field:Version
    @field:Column(nullable = false)
    var persistenceVersion: Long = 0
        protected set

    @field:Column
    var currentRoundId: Long? = null
        protected set

    @field:Column
    var currentWorkId: Long? = null
        protected set

    @field:JdbcTypeCode(SqlTypes.JSON)
    @field:Column(columnDefinition = "jsonb")
    var followUpTarget: FollowUpTarget? = null
        protected set

    @field:Column(length = 30)
    var followUpReason: String? = null
        protected set

    @field:Column(nullable = false)
    var updatedAt: Instant = createdAt
        protected set

    fun startRound(roundId: Long, now: Instant) {
        requireState(status == RequestStatus.OPEN)
        currentRoundId = roundId
        changed(now)
    }

    fun accept(roundId: Long, workId: Long, now: Instant) {
        requireState(status == RequestStatus.OPEN && currentRoundId == roundId)
        status = RequestStatus.ACCEPTED
        currentWorkId = workId
        changed(now)
    }

    fun followUp(target: FollowUpTarget, reason: String, now: Instant) {
        requireState(
            (status == RequestStatus.OPEN && target.kind == "ROUND" && target.id == currentRoundId) ||
                (status == RequestStatus.ACCEPTED && target.kind == "CANCELLATION"),
        )
        status = RequestStatus.FOLLOW_UP_REQUIRED
        followUpTarget = target
        followUpReason = reason
        changed(now)
    }

    fun decide(expected: Int, target: FollowUpTarget, choice: FollowUpChoice, now: Instant) {
        expectedVersion(version, expected)
        requireState(status == RequestStatus.FOLLOW_UP_REQUIRED && followUpTarget == target)
        status = if (choice == FollowUpChoice.REDISPATCH) RequestStatus.OPEN else RequestStatus.CANCELLED
        followUpTarget = null
        followUpReason = null
        changed(now)
    }

    fun complete(workId: Long, now: Instant) {
        requireState(status == RequestStatus.ACCEPTED && currentWorkId == workId)
        status = RequestStatus.COMPLETED
        changed(now)
    }

    private fun changed(now: Instant) {
        version++
        updatedAt = now
    }
    private fun requireState(value: Boolean) {
        if (!value) throw BusinessException(OperationsErrorCode.OPERATIONS_STATE_CONFLICT)
    }
}
