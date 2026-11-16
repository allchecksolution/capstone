package com.allcheck.reception.domain.entity

import com.allcheck.global.exception.BusinessException
import com.allcheck.global.exception.GlobalErrorCode
import com.allcheck.reception.domain.vo.ReceptionAiOriginal
import com.allcheck.reception.domain.vo.ReceptionAnalysisStage
import com.allcheck.reception.domain.vo.ReceptionAnalysisStatus
import com.allcheck.reception.domain.vo.ReceptionConfirmationData
import com.allcheck.reception.domain.vo.ReceptionData
import com.allcheck.reception.domain.vo.ReceptionProcessingStatus
import com.allcheck.reception.domain.vo.ReceptionReviewStatus
import com.allcheck.reception.exception.ReceptionErrorCode
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Id
import jakarta.persistence.Index
import jakarta.persistence.Table
import jakarta.persistence.Version
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes
import java.time.Instant
import java.time.OffsetDateTime

/** 접수의 소유자·검토 상태·현재 자료 연결·최신 편집값을 소유한다. */
@Entity
@Table(
    name = "receptions",
    indexes = [Index(name = "ix_receptions_owner_received", columnList = "owner_admin_id, received_at")],
)
class Reception(
    @field:Id
    @field:Column(name = "reception_id", nullable = false, length = 64, updatable = false)
    val receptionId: String,
    @field:Column(name = "owner_admin_id", nullable = false, length = 64, updatable = false)
    val ownerAdminId: String,
    @field:Column(name = "received_at", nullable = false, updatable = false)
    val receivedAt: Instant,
    /** 통화 메타데이터의 상대 번호. 관리자가 확인하는 finalData.contactPhone과 구분한다. */
    @field:Column(name = "caller_phone", nullable = true, length = 20, updatable = false)
    val callerPhone: String? = null,
    @field:Column(name = "call_started_at", nullable = true, updatable = false)
    val callStartedAt: OffsetDateTime? = null,
) {
    @field:Enumerated(EnumType.STRING)
    @field:Column(name = "review_status", nullable = false, length = 20)
    var reviewStatus: ReceptionReviewStatus = ReceptionReviewStatus.DRAFT
        protected set

    @field:Column(name = "version", nullable = false)
    var version: Int = 1
        protected set

    /** 업무 버전과 분리한 JPA 동시 수정 감지 값. 외부 계약에는 노출하지 않는다. */
    @field:Version
    @field:Column(name = "persistence_version", nullable = false)
    private var persistenceVersion: Long? = null

    /** 최초 적용된 AI 추출 원본. 관리자의 최신 편집값과 분리한다. */
    @field:JdbcTypeCode(SqlTypes.JSON)
    @field:Column(name = "ai_original", nullable = true, columnDefinition = "jsonb")
    var aiOriginal: ReceptionAiOriginal? = null
        protected set

    /** 최초 AI 자동 채움 또는 마지막 검토 완료로 저장된 현재 값. 검토 전에는 불완전할 수 있다. */
    @field:JdbcTypeCode(SqlTypes.JSON)
    @field:Column(name = "final_data", nullable = false, columnDefinition = "jsonb")
    var finalData: ReceptionData = ReceptionData()
        protected set

    /** 미완료 업로드 예약도 식별한다. ID 존재만으로 원본 재생 가능 여부를 판단하지 않는다. */
    @field:Column(name = "recording_file_id", nullable = true, length = 64)
    var recordingFileId: String? = null
        protected set

    /** DB STT 원문의 식별자이며 S3 파일을 의미하지 않는다. */
    @field:Column(name = "transcript_file_id", nullable = true, length = 64)
    var transcriptFileId: String? = null
        protected set

    @field:Column(name = "latest_analysis_request_id", nullable = true, length = 64)
    var latestAnalysisRequestId: String? = null
        protected set

    @field:Column(name = "request_id", nullable = true, length = 64, unique = true)
    var requestId: String? = null
        protected set

    @field:Column(name = "confirmed_at", nullable = true)
    var confirmedAt: Instant? = null
        protected set

    @field:Column(name = "updated_at", nullable = false)
    var updatedAt: Instant = receivedAt
        protected set

    /**
     * 소유권 확인 후 서버에서 생성한 파일 ID를 예약한다. 아직 검증 완료 원본이나 분석을 뜻하지 않는다.
     * 애플리케이션은 녹음 메타데이터 생성과 이 변경을 같은 트랜잭션으로 저장해야 한다.
     * 미완료 업로드의 URL 재발급은 새 예약을 만들지 않고 기존 파일을 사용한다.
     */
    fun reserveRecording(expectedVersion: Int, recordingFileId: String, now: Instant) {
        if (expectedVersion != version) {
            throw BusinessException(GlobalErrorCode.VERSION_CONFLICT)
        }
        if (requestId != null) {
            throw BusinessException(ReceptionErrorCode.RECEPTION_ALREADY_REGISTERED)
        }
        if (reviewStatus != ReceptionReviewStatus.DRAFT) {
            throw BusinessException(ReceptionErrorCode.RECEPTION_ALREADY_CONFIRMED)
        }
        if (this.recordingFileId != null) {
            throw BusinessException(ReceptionErrorCode.RECORDING_ALREADY_EXISTS)
        }
        require(recordingFileId.isNotBlank() && recordingFileId.length <= 64)

        val nextVersion = Math.incrementExact(version)

        this.recordingFileId = recordingFileId
        updatedAt = now
        version = nextVersion
    }

    /**
     * 애플리케이션은 소유권 확인 후 현재 분석 시도의 상태와 서버 시각을 전달해야 한다.
     * analysisStatus의 null은 분석 시도가 없는 수동 접수에만 사용하며 클라이언트가 지정하지 않는다.
     * 모든 검증·변환을 마친 다음 값을 함께 변경한다. DB 트랜잭션·경합 제어는 애플리케이션과 저장소에서 담당한다.
     */
    fun confirm(
        expectedVersion: Int,
        data: ReceptionConfirmationData,
        analysisStatus: ReceptionAnalysisStatus?,
        now: Instant,
    ) {
        if (expectedVersion != version) {
            throw BusinessException(GlobalErrorCode.VERSION_CONFLICT)
        }
        if (requestId != null) {
            throw BusinessException(ReceptionErrorCode.RECEPTION_ALREADY_REGISTERED)
        }
        if (reviewStatus != ReceptionReviewStatus.DRAFT) {
            throw BusinessException(ReceptionErrorCode.RECEPTION_ALREADY_CONFIRMED)
        }
        validateReviewReadiness(analysisStatus)
        data.validateForConfirmation(now)

        val nextVersion = Math.incrementExact(version)
        val reviewedData = ReceptionData(
            workType = data.workType,
            symptoms = data.symptoms,
            address = data.address,
            desiredVisitFrom = data.desiredVisitFrom,
            desiredVisitTo = data.desiredVisitTo,
            contactPhone = data.contactPhone,
            notes = data.notes,
        )

        finalData = reviewedData
        reviewStatus = ReceptionReviewStatus.CONFIRMED
        confirmedAt = now
        updatedAt = now
        version = nextVersion
    }

    /** 소유권 확인 후 서버 시각으로 검토 확정을 해제한다. 원본·최신값은 보존하고 AI를 다시 실행하지 않는다. */
    fun reopen(expectedVersion: Int, now: Instant) {
        if (expectedVersion != version) {
            throw BusinessException(GlobalErrorCode.VERSION_CONFLICT)
        }
        if (requestId != null) {
            throw BusinessException(ReceptionErrorCode.RECEPTION_ALREADY_REGISTERED)
        }
        if (reviewStatus != ReceptionReviewStatus.CONFIRMED) {
            throw BusinessException(ReceptionErrorCode.RECEPTION_NOT_CONFIRMED)
        }

        val nextVersion = Math.incrementExact(version)

        reviewStatus = ReceptionReviewStatus.DRAFT
        confirmedAt = null
        updatedAt = now
        version = nextVersion
    }

    /** 분석 시도의 applyResultTo에서만 호출한다. 두 Aggregate는 같은 트랜잭션으로 저장한다. */
    internal fun applyAnalysisResult(analysis: ReceptionAnalysis, now: Instant): Boolean {
        if (reviewStatus != ReceptionReviewStatus.DRAFT ||
            requestId != null ||
            aiOriginal != null ||
            analysis.receptionId != receptionId ||
            analysis.requestedByAdminId != ownerAdminId ||
            analysis.receptionVersion != version ||
            analysis.analysisRequestId != latestAnalysisRequestId ||
            analysis.recordingFileId != recordingFileId ||
            analysis.transcriptFileId == null ||
            analysis.transcriptFileId != transcriptFileId ||
            analysis.status != ReceptionAnalysisStatus.PROCESSING ||
            analysis.stage != ReceptionAnalysisStage.RESULT_APPLICATION ||
            analysis.analysisStatus != ReceptionProcessingStatus.SUCCEEDED
        ) {
            return false
        }
        val original = checkNotNull(analysis.aiOriginal)
        require(!now.isBefore(updatedAt)) { "Analysis application time must not precede the reception update." }
        val nextVersion = Math.incrementExact(version)
        // 독립 임시 저장이 없는 MVP에서는 검토 완료로 저장된 전체 값이 비어 있지 않다.
        val nextData = if (finalData == ReceptionData()) {
            ReceptionData(
                workType = original.workType,
                symptoms = original.symptoms,
                address = original.address,
                desiredVisitFrom = original.desiredVisitFrom,
                desiredVisitTo = original.desiredVisitTo,
                notes = original.notes,
            )
        } else {
            finalData
        }

        aiOriginal = original
        finalData = nextData
        version = nextVersion
        updatedAt = now
        return true
    }

    private fun validateReviewReadiness(analysisStatus: ReceptionAnalysisStatus?) {
        if (analysisStatus == ReceptionAnalysisStatus.PENDING ||
            analysisStatus == ReceptionAnalysisStatus.PROCESSING ||
            (latestAnalysisRequestId != null && analysisStatus == null)
        ) {
            throw BusinessException(ReceptionErrorCode.REVIEW_NOT_READY)
        }
    }
}
