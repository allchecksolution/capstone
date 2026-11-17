package com.allcheck.operations.work.domain.entity

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Id
import jakarta.persistence.Table
import jakarta.persistence.Version
import java.time.Instant
import java.util.UUID

enum class WorkStatus { NOT_STARTED, EN_ROUTE, ARRIVED, IN_PROGRESS, REVISIT_REQUIRED, WORK_DONE, CANCELLED }

@Entity
@Table(name = "work_orders")
class WorkOrder(
    @Column(name = "request_id", nullable = false, length = 64, updatable = false) val requestId: String,
    @Column(name = "driver_id", nullable = false, length = 64, updatable = false) val driverId: String,
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20) val status: WorkStatus = WorkStatus.NOT_STARTED,
    @Column(name = "created_at", nullable = false, updatable = false) val createdAt: Instant,
) {
    @Id
    @Column(name = "work_id", nullable = false, length = 64, updatable = false)
    val id: String = UUID.randomUUID().toString()

    @Version
    @Column(nullable = false)
    var version: Long? = null
        protected set
}
