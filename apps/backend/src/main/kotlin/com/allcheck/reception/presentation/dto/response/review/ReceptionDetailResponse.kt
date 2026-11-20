package com.allcheck.reception.presentation.dto.response.review

import com.allcheck.reception.domain.vo.ReceptionProcessingStatus
import com.allcheck.reception.domain.vo.review.ReceptionReviewStatus
import com.allcheck.reception.presentation.dto.response.analysis.ReceptionAiOriginalResponse
import io.swagger.v3.oas.annotations.media.Schema
import java.time.Instant
import java.time.OffsetDateTime

@Schema(name = "ReceptionDetail")
data class ReceptionDetailResponse(
    @field:Schema(description = "접수 식별자")
    val receptionId: String,
    @field:Schema(description = "Backend 접수 수신 시각")
    val receivedAt: Instant,
    @field:Schema(description = "통화 상대 번호. 마지막 네 자리 외 숫자를 마스킹한다.")
    val callerPhoneMasked: String?,
    @field:Schema(description = "관리자 검토 상태")
    val reviewStatus: ReceptionReviewStatus,
    @field:Schema(description = "녹음 원본 검증 상태")
    val recordingStatus: ReceptionProcessingStatus,
    @field:Schema(description = "전사 실행 상태")
    val sttStatus: ReceptionProcessingStatus,
    @field:Schema(description = "정식 원문 보관 상태")
    val transcriptStorageStatus: ReceptionProcessingStatus,
    @field:Schema(description = "구조화 처리 상태. 편집 가능 여부는 reviewEditable을 사용한다.")
    val analysisStatus: ReceptionProcessingStatus,
    @field:Schema(description = "검토 요청에 사용하는 업무 버전")
    val version: Int,
    @field:Schema(description = "미완료 예약을 포함한 녹음 식별자")
    val recordingFileId: String?,
    @field:Schema(description = "DB 원문 식별자")
    val transcriptFileId: String?,
    @field:Schema(description = "현재 분석 시도 식별자")
    val latestAnalysisRequestId: String?,
    @field:Schema(description = "최초 검증 완료 시각")
    val recordingReceivedAt: OffsetDateTime?,
    @field:Schema(description = "AI 상대 날짜 해석의 고정 기준 시각")
    val referenceTime: OffsetDateTime?,
    @field:Schema(description = "기준 시간대 Asia/Seoul")
    val timeZone: String,
    @field:Schema(description = "DB에 보관한 변경 없는 STT 원문")
    val transcript: String?,
    @field:Schema(description = "최초 반영된 AI 원본")
    val aiOriginal: ReceptionAiOriginalResponse?,
    @field:Schema(description = "최신 편집값. 값이 없으면 빈 객체")
    val finalData: ReceptionEditableResponse,
    @field:Schema(description = "현재 유효한 검토 확정 시각")
    val confirmedAt: Instant?,
    @field:Schema(description = "등록된 의뢰 식별자")
    val requestId: String?,
    @field:Schema(description = "DRAFT이며 의뢰 미등록이고 업로드·분석 처리가 끝나 입력·수정 가능한지 여부. 필수값 충족과는 별개다.")
    val reviewEditable: Boolean,
)
