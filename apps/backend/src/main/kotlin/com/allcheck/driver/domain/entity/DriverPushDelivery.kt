package com.allcheck.driver.domain.entity

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import jakarta.persistence.UniqueConstraint
import java.time.Instant
import java.util.UUID

@Entity
@Table(name = "driver_push_deliveries", uniqueConstraints = [UniqueConstraint(columnNames = ["event_id", "installation_id"])])
class DriverPushDelivery(
    @Column(name = "event_id", nullable = false, length = 64, updatable = false) val eventId: String,
    @Column(name = "recipient_account_id", nullable = false, length = 64, updatable = false) val recipientAccountId: String,
    @Column(name = "installation_id", nullable = false, length = 64, updatable = false) val installationId: String,
    @Column(name = "created_at", nullable = false, updatable = false) val createdAt: Instant,
) {
    @Id
    @Column(name = "delivery_id", nullable = false, length = 64, updatable = false)
    val id: String = UUID.randomUUID().toString()

    @Column(nullable = false, length = 24)
    var status: String = "PENDING"
        protected set

    @Column(name = "claim_id", length = 64)
    var claimId: String? = null
        protected set

    @Column(name = "lease_expires_at")
    var leaseExpiresAt: Instant? = null
        protected set

    @Column(name = "attempt_count", nullable = false)
    var attemptCount: Int = 0
        protected set

    @Column(name = "provider_message_id", columnDefinition = "text")
    var providerMessageId: String? = null
        protected set

    @Column(name = "error_code", length = 64)
    var errorCode: String? = null
        protected set

    @Column(name = "completed_at")
    var completedAt: Instant? = null
        protected set

    fun claim(now: Instant, expiresAt: Instant): String {
        status = "PROCESSING"
        claimId = UUID.randomUUID().toString()
        leaseExpiresAt = expiresAt
        attemptCount++
        return checkNotNull(claimId)
    }

    fun finish(expectedClaim: String, outcome: String, messageId: String?, errorCode: String?, now: Instant) {
        if (status != "PROCESSING" || claimId != expectedClaim) return
        status = outcome
        providerMessageId = messageId
        this.errorCode = errorCode
        completedAt = now
        claimId = null
        leaseExpiresAt = null
    }
}
