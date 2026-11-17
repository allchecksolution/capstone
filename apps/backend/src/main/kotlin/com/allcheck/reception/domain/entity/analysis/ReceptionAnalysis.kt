package com.allcheck.reception.domain.entity.analysis

import com.allcheck.reception.domain.entity.Reception
import com.allcheck.reception.domain.vo.ReceptionProcessingStatus
import com.allcheck.reception.domain.vo.analysis.ReceptionAiOriginal
import com.allcheck.reception.domain.vo.analysis.ReceptionAnalysisFailureReason
import com.allcheck.reception.domain.vo.analysis.ReceptionAnalysisStage
import com.allcheck.reception.domain.vo.analysis.ReceptionAnalysisStatus
import com.allcheck.reception.domain.vo.analysis.ReceptionAnalysisTransition
import com.allcheck.reception.domain.vo.analysis.ReceptionStructuredResult
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
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
    @field:Column(name = "reception_id", nullable = false, updatable = false)
    val receptionId: Long,
    @field:Column(name = "recording_file_id", nullable = false, updatable = false)
    val recordingFileId: Long,
    @field:Column(name = "reception_version", nullable = false, updatable = false)
    val receptionVersion: Int,
    @field:Column(name = "requested_by_admin_id", nullable = false, updatable = false)
    val requestedByAdminId: Long,
    @field:Column(name = "reference_time", nullable = false, updatable = false)
    val referenceTime: Instant,
    @field:Column(name = "created_at", nullable = false, updatable = false)
    val createdAt: Instant,
) {
    @field:Id
    @field:GeneratedValue(strategy = GenerationType.IDENTITY)
    @field:Column(name = "analysis_request_id", nullable = false, updatable = false)
    var analysisRequestId: Long? = null
        protected set

    init {
        require(receptionId > 0)
        require(recordingFileId > 0)
        require(requestedByAdminId > 0)
        require(receptionVersion >= 1)
        require(!createdAt.isBefore(referenceTime)) { "Analysis acceptance must not precede recording reception." }
    }

    // 생성 경로는 최초 FULL 분석만 제공한다. V1의 재분석 열은 기존 행의 호환을 위해 매핑한다.
    @field:Column(name = "mode", nullable = false, length = 20, updatable = false)
    val mode: String = "FULL"

    @field:Column(name = "is_initial", nullable = false, updatable = false)
    val isInitial: Boolean = true

    @field:Column(name = "source_transcript_file_id", nullable = true, updatable = false)
    private val sourceTranscriptFileId: Long? = null

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

    @field:Column(name = "transcript_file_id", nullable = true)
    var transcriptFileId: Long? = null
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

    /**
     * 원문을 변경하거나 Entity에 임시 보관하지 않는다. ADVANCED에서만 같은 문자열을 DB 원문으로 만든다.
     * 전사 성공 체크포인트와 실제 원문 저장을 구분하여 DB 저장 실패 시에도 STT 성공 사실을 유지한다.
     */
    fun receiveTranscription(
        workerId: String,
        transcript: String,
        traceId: String,
        now: Instant,
    ): ReceptionAnalysisTransition {
        if (!isOwnedProcessingStage(workerId, ReceptionAnalysisStage.STT)) {
            return ReceptionAnalysisTransition.IGNORED
        }
        validateTransition(traceId, now)
        if (expireIfDue(now, traceId)) {
            return ReceptionAnalysisTransition.FAILED
        }
        if (transcript.isBlank()) {
            sttStatus = ReceptionProcessingStatus.FAILED
            transcriptStorageStatus = ReceptionProcessingStatus.NOT_AVAILABLE
            analysisStatus = ReceptionProcessingStatus.NOT_AVAILABLE
            finishUnapplied(ReceptionAnalysisFailureReason.STT_NO_CONTENT, traceId, now)
            return ReceptionAnalysisTransition.FAILED
        }
        require(transcript.codePointCount(0, transcript.length) <= ReceptionTranscript.MAX_CODE_POINTS) {
            "Transcript exceeds the AI contract limit."
        }

        sttStatus = ReceptionProcessingStatus.SUCCEEDED
        transcriptStorageStatus = ReceptionProcessingStatus.PROCESSING
        stage = ReceptionAnalysisStage.TRANSCRIPT_STORAGE
        updatedAt = now
        return ReceptionAnalysisTransition.ADVANCED
    }

    /** 원문 INSERT 전에 확인한다. 만료 시에는 원문을 생성하지 않고 실패만 저장한다. */
    fun prepareTranscriptStorage(workerId: String, traceId: String, now: Instant): ReceptionAnalysisTransition {
        if (!isOwnedProcessingStage(workerId, ReceptionAnalysisStage.TRANSCRIPT_STORAGE)) return ReceptionAnalysisTransition.IGNORED
        validateTransition(traceId, now)
        if (expireIfDue(now, traceId)) return ReceptionAnalysisTransition.FAILED
        return ReceptionAnalysisTransition.ADVANCED
    }

    fun failTranscription(
        workerId: String,
        reason: ReceptionAnalysisFailureReason,
        traceId: String,
        now: Instant,
    ): Boolean {
        if (!isOwnedProcessingStage(workerId, ReceptionAnalysisStage.STT)) return false
        require(reason == ReceptionAnalysisFailureReason.AI_STT_FAILED || reason == ReceptionAnalysisFailureReason.AI_TIMEOUT)
        validateTransition(traceId, now)
        if (expireIfDue(now, traceId)) return true
        sttStatus = ReceptionProcessingStatus.FAILED
        transcriptStorageStatus = ReceptionProcessingStatus.NOT_AVAILABLE
        analysisStatus = ReceptionProcessingStatus.NOT_AVAILABLE
        finishUnapplied(reason, traceId, now)
        return true
    }

    /**
     * 원문 INSERT로 할당받은 ID를 연결한다. 애플리케이션은 삽입 전 실행권·단계·기한을 확인해야 한다.
     * 원문과 ADVANCED 연결은 같은 트랜잭션으로 커밋한다. 실패/무시이면 새 원문 INSERT도 롤백해야 한다.
     * 만료 등 종료 기록이 필요하면 원문을 롤백한 뒤 별도 트랜잭션으로 실패·알림 의도를 저장한다.
     * 구조화 호출은 커밋 이후 별도 시작하며 여기서는 STRUCTURE/PENDING으로 준비한다.
     */
    fun attachTranscript(
        workerId: String,
        transcript: ReceptionTranscript,
        traceId: String,
        now: Instant,
    ): ReceptionAnalysisTransition {
        if (!isOwnedProcessingStage(workerId, ReceptionAnalysisStage.TRANSCRIPT_STORAGE)) {
            return ReceptionAnalysisTransition.IGNORED
        }
        validateTransition(traceId, now)
        if (expireIfDue(now, traceId)) {
            return ReceptionAnalysisTransition.FAILED
        }
        require(transcript.producedByAnalysisRequestId == analysisRequestId)
        require(transcript.receptionId == receptionId && transcript.recordingFileId == recordingFileId)
        require(!transcript.storedAt.isBefore(updatedAt) && !transcript.storedAt.isAfter(now)) {
            "Transcript storage time must follow transcription and not exceed the transition time."
        }

        transcriptFileId = checkNotNull(transcript.transcriptFileId) { "Persist the transcript before attaching its generated ID." }
        transcriptStorageStatus = ReceptionProcessingStatus.SUCCEEDED
        stage = ReceptionAnalysisStage.STRUCTURE
        updatedAt = now
        return ReceptionAnalysisTransition.ADVANCED
    }

    /** DB 원문 저장 트랜잭션을 롤백한 뒤, 별도 트랜잭션에서 실패와 알림 발행 의도를 함께 저장해야 한다. */
    fun failTranscriptStorage(workerId: String, traceId: String, now: Instant): Boolean {
        if (!isOwnedProcessingStage(workerId, ReceptionAnalysisStage.TRANSCRIPT_STORAGE)) {
            return false
        }
        validateTransition(traceId, now)
        if (expireIfDue(now, traceId)) {
            return true
        }

        transcriptStorageStatus = ReceptionProcessingStatus.FAILED
        analysisStatus = ReceptionProcessingStatus.NOT_AVAILABLE
        finishUnapplied(ReceptionAnalysisFailureReason.TRANSCRIPT_STORAGE_FAILED, traceId, now)
        return true
    }

    /** 원문 보관 커밋 이후에만 시작한다. 이 변경도 커밋한 뒤 외부 구조화 API를 호출한다. */
    fun startStructuring(workerId: String, traceId: String, now: Instant): ReceptionAnalysisTransition {
        if (!isOwnedProcessingStage(workerId, ReceptionAnalysisStage.STRUCTURE) ||
            analysisStatus != ReceptionProcessingStatus.PENDING
        ) {
            return ReceptionAnalysisTransition.IGNORED
        }
        validateTransition(traceId, now)
        if (expireIfDue(now, traceId)) {
            return ReceptionAnalysisTransition.FAILED
        }
        check(sttStatus == ReceptionProcessingStatus.SUCCEEDED)
        check(transcriptStorageStatus == ReceptionProcessingStatus.SUCCEEDED && transcriptFileId != null)

        analysisStatus = ReceptionProcessingStatus.PROCESSING
        updatedAt = now
        return ReceptionAnalysisTransition.ADVANCED
    }

    /** 분석 시도의 응답 원본을 보관한다. 현재 접수에 적용되기 전까지 전체 분석은 PROCESSING이다. */
    fun receiveStructuredResult(
        workerId: String,
        result: ReceptionStructuredResult,
        traceId: String,
        now: Instant,
    ): ReceptionAnalysisTransition {
        if (!isOwnedProcessingStage(workerId, ReceptionAnalysisStage.STRUCTURE) ||
            analysisStatus != ReceptionProcessingStatus.PROCESSING
        ) {
            return ReceptionAnalysisTransition.IGNORED
        }
        validateTransition(traceId, now)
        if (expireIfDue(now, traceId)) {
            return ReceptionAnalysisTransition.FAILED
        }
        val missing = result.missingFields

        aiOriginal = result.aiOriginal
        storedMissingFields = missing
        analysisStatus = ReceptionProcessingStatus.SUCCEEDED
        stage = ReceptionAnalysisStage.RESULT_APPLICATION
        updatedAt = now
        return ReceptionAnalysisTransition.ADVANCED
    }

    fun failStructuring(
        workerId: String,
        reason: ReceptionAnalysisFailureReason,
        traceId: String,
        now: Instant,
    ): Boolean {
        if (!isOwnedProcessingStage(workerId, ReceptionAnalysisStage.STRUCTURE) ||
            analysisStatus != ReceptionProcessingStatus.PROCESSING
        ) {
            return false
        }
        validateTransition(traceId, now)
        if (expireIfDue(now, traceId)) {
            return true
        }
        require(reason == ReceptionAnalysisFailureReason.AI_STRUCTURING_FAILED || reason == ReceptionAnalysisFailureReason.AI_TIMEOUT)

        analysisStatus = ReceptionProcessingStatus.FAILED
        finishUnapplied(reason, traceId, now)
        return true
    }

    /**
     * 접수 변경·분석 종료·알림 발행 의도를 같은 애플리케이션 트랜잭션에서 저장해야 한다.
     * 시각/버전/자료 연결이 유효한 결과만 한 번 적용하며, 오래된 결과는 접수를 바꾸지 않고 DISCARDED로 닫는다.
     */
    fun applyResultTo(
        reception: Reception,
        workerId: String,
        traceId: String,
        now: Instant,
    ): ReceptionAnalysisTransition {
        if (!isOwnedProcessingStage(workerId, ReceptionAnalysisStage.RESULT_APPLICATION) ||
            analysisStatus != ReceptionProcessingStatus.SUCCEEDED
        ) {
            return ReceptionAnalysisTransition.IGNORED
        }
        validateTransition(traceId, now)
        if (expireIfDue(now, traceId)) {
            return ReceptionAnalysisTransition.FAILED
        }
        if (!reception.applyAnalysisResult(this, now)) {
            finishUnapplied(
                ReceptionAnalysisFailureReason.STALE_ANALYSIS_RESULT,
                traceId,
                now,
                terminalStatus = ReceptionAnalysisStatus.DISCARDED,
            )
            return ReceptionAnalysisTransition.FAILED
        }

        status = ReceptionAnalysisStatus.SUCCEEDED
        stage = ReceptionAnalysisStage.COMPLETED
        resultApplied = true
        completedAt = now
        updatedAt = now
        claimedBy = null
        leaseExpiresAt = null
        return ReceptionAnalysisTransition.ADVANCED
    }

    /**
     * 기한과 같거나 지난 PROCESSING을 종료한다. 원격 실행의 중단·취소를 의미하지 않으며 재실행하지 않는다.
     * 이미 보관한 원문과 성공한 앞 단계는 보존한다. 실패 변경·알림 의도의 원자적 저장은 애플리케이션의 책임이다.
     */
    fun expireIfDue(now: Instant, traceId: String): Boolean {
        if (status != ReceptionAnalysisStatus.PROCESSING || !isInitial || mode != "FULL") {
            return false
        }
        val deadline = checkNotNull(leaseExpiresAt)
        if (now.isBefore(deadline)) {
            return false
        }
        validateTransition(traceId, now)

        when {
            sttStatus != ReceptionProcessingStatus.SUCCEEDED -> {
                sttStatus = ReceptionProcessingStatus.FAILED
                transcriptStorageStatus = ReceptionProcessingStatus.NOT_AVAILABLE
                analysisStatus = ReceptionProcessingStatus.NOT_AVAILABLE
            }
            transcriptStorageStatus != ReceptionProcessingStatus.SUCCEEDED -> {
                transcriptStorageStatus = ReceptionProcessingStatus.FAILED
                analysisStatus = ReceptionProcessingStatus.NOT_AVAILABLE
            }
            else -> analysisStatus = ReceptionProcessingStatus.FAILED
        }
        finishUnapplied(ReceptionAnalysisFailureReason.ANALYSIS_TIMEOUT, traceId, now)
        return true
    }

    private fun isOwnedProcessingStage(
        workerId: String,
        expectedStage: ReceptionAnalysisStage,
    ): Boolean = status == ReceptionAnalysisStatus.PROCESSING &&
        isInitial &&
        mode == "FULL" &&
        claimedBy == workerId &&
        stage == expectedStage

    private fun validateTransition(traceId: String, now: Instant) {
        require(traceId.isNotBlank())
        require(!now.isBefore(updatedAt)) { "Analysis transition time must not precede its last update." }
    }

    private fun finishUnapplied(
        reason: ReceptionAnalysisFailureReason,
        traceId: String,
        now: Instant,
        terminalStatus: ReceptionAnalysisStatus = ReceptionAnalysisStatus.FAILED,
    ) {
        status = terminalStatus
        errorCode = reason.name
        errorMessage = reason.message
        this.traceId = traceId
        completedAt = now
        updatedAt = now
        claimedBy = null
        leaseExpiresAt = null
    }
}
