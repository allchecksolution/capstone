package com.allcheck.driver.domain.entity.notification

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import jakarta.persistence.UniqueConstraint
import java.time.Instant

@Entity
@Table(name = "driver_notification_events", uniqueConstraints = [UniqueConstraint(columnNames = ["driver_id", "revision", "event_type"])])
class DriverNotificationEvent(
    @Column(name = "driver_id", nullable = false, updatable = false) val driverId: Long,
    @Column(nullable = false, updatable = false) val revision: Long,
    @Column(name = "event_type", nullable = false, length = 32, updatable = false) val eventType: String,
    @Column(name = "recipient_account_id", updatable = false) val recipientAccountId: Long?,
    @Column(name = "created_at", nullable = false, updatable = false) val createdAt: Instant,
) {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "event_id", nullable = false, updatable = false)
    var id: Long? = null
        protected set

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
