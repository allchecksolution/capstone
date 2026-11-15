package com.allcheck.reception.domain.entity

import com.allcheck.global.exception.BusinessException
import com.allcheck.global.exception.GlobalErrorCode
import com.allcheck.reception.domain.vo.ReceptionAiOriginal
import com.allcheck.reception.domain.vo.ReceptionAnalysisStatus
import com.allcheck.reception.domain.vo.ReceptionConfirmationData
import com.allcheck.reception.domain.vo.ReceptionData
import com.allcheck.reception.domain.vo.ReceptionReviewStatus
import com.allcheck.reception.exception.ReceptionErrorCode
import java.time.Instant
import java.time.OffsetDateTime

/** 접수의 소유자·검토 상태·현재 자료 연결·최신 편집값을 소유한다. JPA 매핑은 후속 단계에서 추가한다. */
class Reception(
    val receptionId: String,
    val ownerAdminId: String,
    val receivedAt: Instant,
    /** 통화 메타데이터의 상대 번호. 관리자가 확인하는 finalData.contactPhone과 구분한다. */
    val callerPhone: String? = null,
    val callStartedAt: OffsetDateTime? = null,
) {
    var reviewStatus: ReceptionReviewStatus = ReceptionReviewStatus.DRAFT
        private set

    var version: Int = 1
        private set

    /** 최초 적용된 AI 추출 원본. 관리자의 최신 편집값과 분리한다. */
    var aiOriginal: ReceptionAiOriginal? = null
        private set

    /** 최초 AI 자동 채움 또는 마지막 검토 완료로 저장된 현재 값. 검토 전에는 불완전할 수 있다. */
    var finalData: ReceptionData = ReceptionData()
        private set

    /** 미완료 업로드 예약도 식별한다. ID 존재만으로 원본 재생 가능 여부를 판단하지 않는다. */
    var recordingFileId: String? = null
        private set

    /** DB STT 원문의 식별자이며 S3 파일을 의미하지 않는다. */
    var transcriptFileId: String? = null
        private set

    var latestAnalysisRequestId: String? = null
        private set

    var requestId: String? = null
        private set

    var confirmedAt: Instant? = null
        private set

    var updatedAt: Instant = receivedAt
        private set

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

    private fun validateReviewReadiness(analysisStatus: ReceptionAnalysisStatus?) {
        if (analysisStatus == ReceptionAnalysisStatus.PENDING ||
            analysisStatus == ReceptionAnalysisStatus.PROCESSING ||
            (latestAnalysisRequestId != null && analysisStatus == null)
        ) {
            throw BusinessException(ReceptionErrorCode.REVIEW_NOT_READY)
        }
    }
}
