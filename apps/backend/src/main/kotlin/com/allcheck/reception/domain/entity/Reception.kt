package com.allcheck.reception.domain.entity

import com.allcheck.reception.domain.vo.ReceptionAiOriginal
import com.allcheck.reception.domain.vo.ReceptionData
import com.allcheck.reception.domain.vo.ReceptionReviewStatus
import java.time.Instant
import java.time.OffsetDateTime

/** 접수의 소유자·검토 상태·현재 자료 연결·최신 편집값을 소유한다. 상태 변경 동작과 JPA 매핑은 후속 단계에서 추가한다. */
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
}
