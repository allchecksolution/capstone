package com.allcheck.reception.domain.entity

import com.allcheck.reception.domain.vo.ReceptionAnalysisStatus
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import jakarta.persistence.UniqueConstraint
import org.hibernate.annotations.Immutable
import java.time.Instant

/** 분석 종료와 같은 트랜잭션에 저장하는 발행 의도. 기기 토큰·원문·서명 URL을 담지 않는다. */
@Entity
@Immutable
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

    @field:Column(name = "status", nullable = false, length = 20, updatable = false)
    val status: String = "PENDING"

    @field:Column(name = "updated_at", nullable = false, updatable = false)
    val updatedAt: Instant = createdAt

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
