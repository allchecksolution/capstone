package com.allcheck.driver.domain.entity.registration

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import jakarta.persistence.UniqueConstraint
import java.time.Instant

@Entity
@Table(name = "driver_registration_history", uniqueConstraints = [UniqueConstraint(columnNames = ["driver_id", "revision", "event_type"])])
class DriverHistory(
    @Column(name = "driver_id", nullable = false, updatable = false) val driverId: Long,
    @Column(nullable = false, updatable = false) val revision: Long,
    @Column(name = "event_type", nullable = false, length = 16, updatable = false) val eventType: String,
    @Column(name = "actor_account_id", nullable = false, updatable = false) val actorAccountId: Long,
    @Column(name = "occurred_at", nullable = false, updatable = false) val occurredAt: Instant,
    @Column(length = 1000, updatable = false) val reason: String? = null,
    @Column(name = "request_snapshot", columnDefinition = "text", updatable = false) val requestSnapshot: String? = null,
) {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "history_id", nullable = false, updatable = false)
    var id: Long? = null
        protected set
}
