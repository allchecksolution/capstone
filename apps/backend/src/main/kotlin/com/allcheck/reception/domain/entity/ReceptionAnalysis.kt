package com.allcheck.reception.domain.entity

import com.allcheck.reception.domain.vo.ReceptionAiOriginal
import com.allcheck.reception.domain.vo.ReceptionAnalysisStage
import com.allcheck.reception.domain.vo.ReceptionAnalysisStatus
import com.allcheck.reception.domain.vo.ReceptionProcessingStatus
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Id
import jakarta.persistence.Index
import jakarta.persistence.Table
import jakarta.persistence.UniqueConstraint
import jakarta.persistence.Version
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes
import java.time.Instant

/** 최초 분석의 고정 문맥과 실행 상태를 소유한다. PENDING 행이 내구성 있는 최초 실행 의도다. */
@Entity
@Table(
    name = "reception_analysis_requests",
    indexes = [Index(name = "ix_reception_analysis_requests_history", columnList = "reception_id, created_at")],
    uniqueConstraints = [
        UniqueConstraint(
            name = "uq_reception_analysis_requests_context",
            columnNames = ["analysis_request_id", "recording_file_id", "reception_id"],
        ),
        UniqueConstraint(name = "uq_reception_analysis_requests_reception", columnNames = ["analysis_request_id", "reception_id"]),
    ],
)
class ReceptionAnalysis(
    @field:Id
    @field:Column(name = "analysis_request_id", nullable = false, length = 64, updatable = false)
    val analysisRequestId: String,
    @field:Column(name = "reception_id", nullable = false, length = 64, updatable = false)
    val receptionId: String,
    @field:Column(name = "recording_file_id", nullable = false, length = 64, updatable = false)
    val recordingFileId: String,
    @field:Column(name = "reception_version", nullable = false, updatable = false)
    val receptionVersion: Int,
    @field:Column(name = "requested_by_admin_id", nullable = false, length = 64, updatable = false)
    val requestedByAdminId: String,
    @field:Column(name = "reference_time", nullable = false, updatable = false)
    val referenceTime: Instant,
    @field:Column(name = "created_at", nullable = false, updatable = false)
    val createdAt: Instant,
) {
    init {
        require(analysisRequestId.isNotBlank() && analysisRequestId.length <= 64)
        require(receptionId.isNotBlank() && receptionId.length <= 64)
        require(recordingFileId.isNotBlank() && recordingFileId.length <= 64)
        require(requestedByAdminId.isNotBlank() && requestedByAdminId.length <= 64)
        require(receptionVersion >= 1)
        require(!createdAt.isBefore(referenceTime)) { "Analysis acceptance must not precede recording reception." }
    }

    // 생성 경로는 최초 FULL 분석만 제공한다. V1의 재분석 열은 기존 행의 호환을 위해 매핑한다.
    @field:Column(name = "mode", nullable = false, length = 20, updatable = false)
    val mode: String = "FULL"

    @field:Column(name = "is_initial", nullable = false, updatable = false)
    val isInitial: Boolean = true

    @field:Column(name = "source_transcript_file_id", nullable = true, length = 64, updatable = false)
    private val sourceTranscriptFileId: String? = null

    @field:Column(name = "time_zone", nullable = false, length = 64, updatable = false)
    val timeZone: String = "Asia/Seoul"

    @field:Enumerated(EnumType.STRING)
    @field:Column(name = "status", nullable = false, length = 20)
    var status: ReceptionAnalysisStatus = ReceptionAnalysisStatus.PENDING
        protected set

    @field:Enumerated(EnumType.STRING)
    @field:Column(name = "stage", nullable = false, length = 30)
    var stage: ReceptionAnalysisStage = ReceptionAnalysisStage.QUEUED
        protected set

    @field:Enumerated(EnumType.STRING)
    @field:Column(name = "stt_status", nullable = false, length = 20)
    var sttStatus: ReceptionProcessingStatus = ReceptionProcessingStatus.PENDING
        protected set

    @field:Enumerated(EnumType.STRING)
    @field:Column(name = "transcript_storage_status", nullable = false, length = 20)
    var transcriptStorageStatus: ReceptionProcessingStatus = ReceptionProcessingStatus.PENDING
        protected set

    @field:Enumerated(EnumType.STRING)
    @field:Column(name = "analysis_status", nullable = false, length = 20)
    var analysisStatus: ReceptionProcessingStatus = ReceptionProcessingStatus.PENDING
        protected set

    @field:Column(name = "transcript_file_id", nullable = true, length = 64)
    var transcriptFileId: String? = null
        protected set

    @field:Column(name = "result_applied", nullable = false)
    var resultApplied: Boolean = false
        protected set

    @field:JdbcTypeCode(SqlTypes.JSON)
    @field:Column(name = "ai_original", nullable = true, columnDefinition = "jsonb")
    var aiOriginal: ReceptionAiOriginal? = null
        protected set

    @field:JdbcTypeCode(SqlTypes.JSON)
    @field:Column(name = "missing_fields", nullable = true, columnDefinition = "jsonb")
    private var storedMissingFields: List<String>? = null

    val missingFields: List<String>?
        get() = storedMissingFields?.toList()

    @field:Column(name = "error_code", nullable = true, columnDefinition = "text")
    var errorCode: String? = null
        protected set

    @field:Column(name = "error_message", nullable = true, columnDefinition = "text")
    var errorMessage: String? = null
        protected set

    @field:Column(name = "trace_id", nullable = true, columnDefinition = "text")
    var traceId: String? = null
        protected set

    @field:Column(name = "claimed_by", nullable = true, length = 128)
    var claimedBy: String? = null
        protected set

    @field:Column(name = "lease_expires_at", nullable = true)
    var leaseExpiresAt: Instant? = null
        protected set

    @field:Column(name = "started_at", nullable = true)
    var startedAt: Instant? = null
        protected set

    @field:Column(name = "completed_at", nullable = true)
    var completedAt: Instant? = null
        protected set

    @field:Column(name = "updated_at", nullable = false)
    var updatedAt: Instant = createdAt
        protected set

    @field:Version
    @field:Column(name = "persistence_version", nullable = false)
    private var persistenceVersion: Long? = null

    /**
     * 애플리케이션은 실행권 변경의 커밋에 성공한 뒤에만 외부 AI를 호출해야 한다.
     * 처리 중·종료된 시도는 기한이 지나도 재점유하지 않는다. 처리 기한은 내부 설정으로 전달한다.
     * 두 트랜잭션이 모두 true를 받아도 @Version 검사로 하나만 커밋되며 패자는 외부 호출을 수행하지 않는다.
     */
    fun claim(workerId: String, now: Instant, leaseExpiresAt: Instant): Boolean {
        if (status != ReceptionAnalysisStatus.PENDING || !isInitial || mode != "FULL") {
            return false
        }
        require(workerId.isNotBlank() && workerId.length <= 128)
        require(!now.isBefore(createdAt)) { "Analysis start time must not precede acceptance." }
        require(leaseExpiresAt.isAfter(now)) { "Analysis claim deadline must follow its start." }

        status = ReceptionAnalysisStatus.PROCESSING
        stage = ReceptionAnalysisStage.STT
        sttStatus = ReceptionProcessingStatus.PROCESSING
        claimedBy = workerId
        this.leaseExpiresAt = leaseExpiresAt
        startedAt = now
        updatedAt = now
        return true
    }
}
