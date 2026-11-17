package com.allcheck.reception.domain.entity.notification

import com.allcheck.reception.domain.entity.analysis.ReceptionAnalysis
import com.allcheck.reception.domain.vo.analysis.ReceptionAnalysisStatus
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import jakarta.persistence.UniqueConstraint
import jakarta.persistence.Version
import java.time.Instant

/** 분석 종료와 같은 트랜잭션에 저장하는 발행 의도. 기기 토큰·원문·서명 URL을 담지 않는다. */
@Entity
@Table(name = "reception_analysis_events", uniqueConstraints = [UniqueConstraint(name = "uq_reception_analysis_events_attempt", columnNames = ["analysis_request_id"])])
class ReceptionAnalysisEvent(
    @field:Column(name = "analysis_request_id", nullable = false, updatable = false)
    val analysisRequestId: Long,
    @field:Column(name = "reception_id", nullable = false, updatable = false)
    val receptionId: Long,
    @field:Column(name = "recipient_admin_id", nullable = false, updatable = false)
    val recipientAdminId: Long,
    @field:Column(name = "event_type", nullable = false, length = 40, updatable = false)
    val eventType: String,
    @field:Column(name = "created_at", nullable = false, updatable = false)
    val createdAt: Instant,
) {
    @field:Id
    @field:GeneratedValue(strategy = GenerationType.IDENTITY)
    @field:Column(name = "event_id", nullable = false, updatable = false)
    var eventId: Long? = null
        protected set

    @field:Column(name = "status", nullable = false, length = 20)
    var status: String = "PENDING"
        protected set

    @field:Column(name = "completed_at")
    var completedAt: Instant? = null
        protected set

    @field:Column(name = "updated_at", nullable = false)
    var updatedAt: Instant = createdAt
        protected set

    @field:Version
    @field:Column(name = "persistence_version", nullable = false)
    private var persistenceVersion: Long? = null

    fun finishExpansion(hasRecipients: Boolean, now: Instant) {
        check(status == "PENDING")
        require(!now.isBefore(createdAt))
        status = if (hasRecipients) "EXPANDED" else "SKIPPED"
        completedAt = now
        updatedAt = now
    }

    companion object {
        fun completed(analysis: ReceptionAnalysis, now: Instant): ReceptionAnalysisEvent {
            require(analysis.status in setOf(ReceptionAnalysisStatus.SUCCEEDED, ReceptionAnalysisStatus.FAILED, ReceptionAnalysisStatus.DISCARDED))
            return ReceptionAnalysisEvent(
                checkNotNull(analysis.analysisRequestId),
                analysis.receptionId,
                analysis.requestedByAdminId,
                if (analysis.status == ReceptionAnalysisStatus.SUCCEEDED) "RECEPTION_ANALYSIS_SUCCEEDED" else "RECEPTION_ANALYSIS_FAILED",
                now,
            )
        }
    }
}
