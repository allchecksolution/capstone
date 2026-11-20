package com.allcheck.driver.domain.entity.notification

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import jakarta.persistence.UniqueConstraint
import java.time.Instant
import java.util.UUID

@Entity
@Table(name = "driver_notification_events", uniqueConstraints = [UniqueConstraint(columnNames = ["driver_id", "revision", "event_type"])])
class DriverNotificationEvent(
    @Column(name = "driver_id", nullable = false, length = 64, updatable = false) val driverId: String,
    @Column(nullable = false, updatable = false) val revision: Long,
    @Column(name = "event_type", nullable = false, length = 32, updatable = false) val eventType: String,
    @Column(name = "recipient_account_id", length = 64, updatable = false) val recipientAccountId: String?,
    @Column(name = "created_at", nullable = false, updatable = false) val createdAt: Instant,
) {
    @Id
    @Column(name = "event_id", nullable = false, length = 64, updatable = false)
    val id: String = UUID.randomUUID().toString()

    @Column(nullable = false, length = 16)
    var status: String = "PENDING"
        protected set

    fun expand() {
        status = "EXPANDED"
    }
    fun skip() {
        status = "SKIPPED"
    }
}
