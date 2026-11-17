package com.allcheck.reception.presentation.dto.response

import io.swagger.v3.oas.annotations.media.Schema
import java.time.OffsetDateTime

@Schema(name = "RecordingUploadCompleted")
data class RecordingUploadCompletedResponse(
    @field:Schema(description = "검증 완료 녹음 식별자") val recordingFileId: String,
    @field:Schema(description = "검증 완료 상태", allowableValues = ["SUCCEEDED"]) val recordingStatus: String,
    @field:Schema(description = "최초 검증 완료 시각. 재통보로 바뀌지 않는다.") val recordingReceivedAt: OffsetDateTime,
    @field:Schema(description = "Asia/Seoul로 표현한 최초 녹음 전달 기준 시각") val referenceTime: OffsetDateTime,
    @field:Schema(description = "최초 분석 접수 응답. 현재 상태는 statusUrl에서 확인한다.") val analysis: AcceptedRecordingAnalysisResponse,
)

data class AcceptedRecordingAnalysisResponse(
    @field:Schema(description = "최초 분석 시도 식별자") val analysisRequestId: String,
    @field:Schema(description = "접수 식별자") val receptionId: String,
    @field:Schema(description = "분석 접수 당시 고정된 업무 버전") val receptionVersion: Int,
    @field:Schema(description = "원본 녹음 식별자") val recordingFileId: String,
    @field:Schema(description = "분석 상태 조회 경로", format = "uri-reference") val statusUrl: String,
    @field:Schema(description = "최초 전체 분석", allowableValues = ["FULL"]) val mode: String = "FULL",
    @field:Schema(description = "분석 접수 당시 상태", allowableValues = ["PENDING"]) val status: String = "PENDING",
)
