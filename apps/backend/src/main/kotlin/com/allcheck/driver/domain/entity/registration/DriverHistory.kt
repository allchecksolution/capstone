package com.allcheck.driver.domain.entity.registration

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import jakarta.persistence.UniqueConstraint
import java.time.Instant
import java.util.UUID

@Entity
@Table(name = "driver_registration_history", uniqueConstraints = [UniqueConstraint(columnNames = ["driver_id", "revision", "event_type"])])
class DriverHistory(
    @Column(name = "driver_id", nullable = false, length = 64, updatable = false) val driverId: String,
    @Column(nullable = false, updatable = false) val revision: Long,
    @Column(name = "event_type", nullable = false, length = 16, updatable = false) val eventType: String,
    @Column(name = "actor_account_id", nullable = false, length = 64, updatable = false) val actorAccountId: String,
    @Column(name = "occurred_at", nullable = false, updatable = false) val occurredAt: Instant,
    @Column(length = 1000, updatable = false) val reason: String? = null,
    @Column(name = "request_snapshot", columnDefinition = "text", updatable = false) val requestSnapshot: String? = null,
) {
    @Id
    @Column(name = "history_id", nullable = false, length = 64, updatable = false)
    val id: String = UUID.randomUUID().toString()
}
