package com.allcheck.reception.presentation.dto.response.analysis

import com.allcheck.reception.domain.vo.ReceptionProcessingStatus
import com.allcheck.reception.domain.vo.analysis.ReceptionAnalysisStage
import com.allcheck.reception.domain.vo.analysis.ReceptionAnalysisStatus
import io.swagger.v3.oas.annotations.media.Schema
import java.time.Instant

@Schema(name = "AnalysisView")
data class ReceptionAnalysisResponse(
    @field:Schema(description = "분석 시도 식별자") val analysisRequestId: String,
    @field:Schema(description = "접수 식별자") val receptionId: String,
    @field:Schema(description = "분석 접수 당시 고정된 업무 버전") val receptionVersion: Int,
    @field:Schema(description = "녹음 식별자") val recordingFileId: String,
    @field:Schema(description = "최초 FULL 분석") val mode: String,
    @field:Schema(description = "분석 전체 상태") val status: ReceptionAnalysisStatus,
    @field:Schema(description = "처리 단계") val stage: ReceptionAnalysisStage,
    @field:Schema(description = "전사 처리 상태") val sttStatus: ReceptionProcessingStatus,
    @field:Schema(description = "원문 보관 상태") val transcriptStorageStatus: ReceptionProcessingStatus,
    @field:Schema(description = "구조화 처리 상태") val analysisStatus: ReceptionProcessingStatus,
    @field:Schema(description = "보관된 DB 원문 식별자") val transcriptFileId: String?,
    @field:Schema(description = "접수의 최초 결과로 반영되었는지 여부") val resultApplied: Boolean,
    @field:Schema(description = "분석 접수 시각") val requestedAt: Instant,
    @field:Schema(description = "종료 시각") val completedAt: Instant?,
    @field:Schema(description = "처리 실패 사유. HTTP 요청 실패와 별개다.") val error: ReceptionAnalysisError?,
    @field:Schema(description = "이 시도가 받은 구조화 원본. 미반영·구조화 후 실패 결과도 구분한다.") val aiOriginal: ReceptionAiOriginalResponse?,
    @field:Schema(description = "AI 응답의 누락 필드 목록. Backend의 확정 필수값 검증과 별개다.") val missingFields: List<String>?,
)

data class ReceptionAnalysisError(
    @field:Schema(description = "저장된 분석 오류 코드") val code: String,
    @field:Schema(description = "안전한 처리 실패 안내") val message: String,
    @field:Schema(description = "Backend 처리 추적 식별자") val traceId: String,
)
