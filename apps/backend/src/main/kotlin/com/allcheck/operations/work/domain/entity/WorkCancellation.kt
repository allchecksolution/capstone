package com.allcheck.operations.work.domain.entity

import com.allcheck.operations.CancellationReason
import com.allcheck.operations.WorkStatus
import com.allcheck.operations.validInput
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.hibernate.annotations.Immutable
import java.time.Instant

@Entity
@Immutable
@Table(name = "work_cancellations")
class WorkCancellation(
    @field:Column(nullable = false, unique = true, updatable = false) val workId: Long,
    @field:Column(nullable = false, updatable = false) val requestId: Long,
    @field:Column(nullable = false, updatable = false) val driverId: Long,
    @field:Enumerated(EnumType.STRING)
    @field:Column(nullable = false, length = 30, updatable = false) val reasonCode: CancellationReason,
    @field:Column(nullable = false, length = 1000, updatable = false) val reasonDetail: String,
    @field:Enumerated(EnumType.STRING)
    @field:Column(nullable = false, length = 30, updatable = false) val previousStatus: WorkStatus,
    @field:Column(nullable = false, updatable = false) val cancelledAt: Instant,
) {
    init {
        validInput(reasonDetail.isNotBlank() && reasonDetail.codePointCount(0, reasonDetail.length) in 1..1000)
    }

    @field:Id
    @field:GeneratedValue(strategy = GenerationType.IDENTITY)
    @field:Column(nullable = false, updatable = false)
    var cancellationId: Long? = null
        protected set
}
