package com.allcheck.operations.dispatch.domain.entity

import com.allcheck.global.exception.BusinessException
import com.allcheck.operations.RoundStatus
import com.allcheck.operations.exception.OperationsErrorCode
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import jakarta.persistence.Version
import java.time.Instant

@Entity
@Table(name = "dispatch_rounds")
class DispatchRound(
    @field:Column(nullable = false, updatable = false) val requestId: Long,
    @field:Column(nullable = false, updatable = false) val createdAt: Instant,
) {
    @field:Id
    @field:GeneratedValue(strategy = GenerationType.IDENTITY)
    @field:Column(nullable = false, updatable = false)
    var roundId: Long? = null
        protected set

    @field:Enumerated(EnumType.STRING)
    @field:Column(nullable = false, length = 30)
    var status: RoundStatus = RoundStatus.SEARCHING
        protected set

    @field:Column(nullable = false)
    var radiusKm: Int = 10
        protected set

    @field:Column
    var closedAt: Instant? = null
        protected set

    @field:Version
    @field:Column(nullable = false)
    var persistenceVersion: Long = 0
        protected set

    fun expand(): Boolean {
        state(status == RoundStatus.SEARCHING)
        radiusKm = when (radiusKm) {
            10 -> 30
            30 -> 50
            else -> return false
        }
        return true
    }

    fun offering() {
        state(status == RoundStatus.SEARCHING)
        status = RoundStatus.OFFERING
    }
    fun assign(now: Instant) {
        state(status == RoundStatus.OFFERING)
        status = RoundStatus.ASSIGNED
        closedAt = now
    }
    fun close(outcome: RoundStatus, now: Instant) {
        state(status in setOf(RoundStatus.SEARCHING, RoundStatus.OFFERING))
        require(outcome !in setOf(RoundStatus.SEARCHING, RoundStatus.OFFERING, RoundStatus.ASSIGNED))
        status = outcome
        closedAt = now
    }
    private fun state(value: Boolean) {
        if (!value) throw BusinessException(OperationsErrorCode.OPERATIONS_STATE_CONFLICT)
    }
}
