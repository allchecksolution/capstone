package com.allcheck.reception.domain.entity

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import jakarta.persistence.UniqueConstraint
import jakarta.persistence.Version
import java.time.Duration
import java.time.Instant

/** 기기별 발송 상태. 토큰과 알림 본문은 보관하지 않는다. FCM 접수는 기기 수신·읽음을 의미하지 않는다. */
@Entity
@Table(name = "reception_analysis_push_deliveries", uniqueConstraints = [UniqueConstraint(name = "uq_reception_analysis_push_deliveries_target", columnNames = ["event_id", "installation_id"])])
class ReceptionPushDelivery(
    @field:Column(name = "event_id", nullable = false, updatable = false) val eventId: Long,
    @field:Column(name = "installation_id", nullable = false, length = 64, updatable = false) val installationId: String,
    @field:Column(name = "created_at", nullable = false, updatable = false) val createdAt: Instant,
) {
    init {
        require(eventId > 0 && installationId.isNotBlank() && installationId.length <= 64)
    }

    @field:Id
    @field:GeneratedValue(strategy = GenerationType.IDENTITY)
    @field:Column(name = "delivery_id", nullable = false, updatable = false)
    var deliveryId: Long? = null
        protected set

    @field:Column(name = "status", nullable = false, length = 25)
    var status: String = "PENDING"
        protected set

    @field:Column(name = "attempt_count", nullable = false)
    var attemptCount: Int = 0
        protected set

    @field:Column(name = "claimed_by", length = 128)
    var claimedBy: String? = null
        protected set

    @field:Column(name = "lease_expires_at")
    var leaseExpiresAt: Instant? = null
        protected set

    @field:Column(name = "provider_message_id", columnDefinition = "text")
    var providerMessageId: String? = null
        protected set

    @field:Column(name = "last_error", columnDefinition = "text")
    var lastError: String? = null
        protected set

    @field:Column(name = "last_failure_code", length = 40)
    var lastFailureCode: String? = null
        protected set

    @field:Column(name = "last_attempt_at")
    var lastAttemptAt: Instant? = null
        protected set

    @field:Column(name = "provider_accepted_at")
    var providerAcceptedAt: Instant? = null
        protected set

    @field:Column(name = "completed_at")
    var completedAt: Instant? = null
        protected set

    @field:Column(name = "next_attempt_at", nullable = false)
    var nextAttemptAt: Instant = createdAt
        protected set

    @field:Column(name = "updated_at", nullable = false)
    var updatedAt: Instant = createdAt
        protected set

    @field:Version
    @field:Column(name = "persistence_version", nullable = false)
    private var persistenceVersion: Long? = null

    fun claim(worker: String, now: Instant, ttl: Duration): Boolean {
        if (status != "PENDING" || now.isBefore(nextAttemptAt)) return false
        require(worker.isNotBlank() && worker.length <= 128 && ttl > Duration.ZERO)
        status = "PROCESSING"
        claimedBy = worker
        leaseExpiresAt = now.plus(ttl)
        lastAttemptAt = now
        attemptCount = Math.incrementExact(attemptCount)
        updatedAt = now
        return true
    }

    fun accepted(worker: String, providerId: String, now: Instant): Boolean {
        if (status != "PROCESSING" || claimedBy != worker || !now.isBefore(checkNotNull(leaseExpiresAt))) return false
        require(providerId.isNotBlank())
        providerMessageId = providerId
        providerAcceptedAt = now
        finish("PROVIDER_ACCEPTED", now)
        return true
    }

    fun fail(worker: String, code: String, retryable: Boolean, retryAt: Instant, maxAttempts: Int, now: Instant): Boolean {
        if (status != "PROCESSING" || claimedBy != worker) return false
        require(code.matches(Regex("[A-Z_]{1,40}")) && maxAttempts > 0 && !retryAt.isBefore(now))
        lastFailureCode = code
        if (retryable && attemptCount < maxAttempts) {
            status = "PENDING"
            nextAttemptAt = retryAt
            clearClaim()
            updatedAt = now
        } else {
            lastError = code
            finish("FAILED", now)
        }
        return true
    }

    fun skip(worker: String, now: Instant): Boolean {
        if (status != "PROCESSING" || claimedBy != worker) return false
        finish("SKIPPED", now)
        return true
    }

    private fun finish(finalStatus: String, now: Instant) {
        status = finalStatus
        completedAt = now
        updatedAt = now
        clearClaim()
    }

    private fun clearClaim() {
        claimedBy = null
        leaseExpiresAt = null
    }
}
