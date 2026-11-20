package com.allcheck.operations.work.domain.entity

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

enum class WorkStatus { NOT_STARTED, EN_ROUTE, ARRIVED, IN_PROGRESS, REVISIT_REQUIRED, WORK_DONE, CANCELLED }

@Entity
@Table(name = "work_orders")
class WorkOrder(
    @Column(name = "request_id", nullable = false, updatable = false) val requestId: Long,
    @Column(name = "driver_id", nullable = false, updatable = false) val driverId: Long,
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20) val status: WorkStatus = WorkStatus.NOT_STARTED,
    @Column(name = "created_at", nullable = false, updatable = false) val createdAt: Instant,
) {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "work_id", nullable = false, updatable = false)
    var id: Long? = null
        protected set

    @Version
    @Column(nullable = false)
    var version: Long? = null
        protected set
}
