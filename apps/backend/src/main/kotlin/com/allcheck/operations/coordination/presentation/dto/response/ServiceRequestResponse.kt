package com.allcheck.operations.coordination.presentation.dto.response

import com.allcheck.operations.RequestStatus
import com.allcheck.operations.WorkType
import io.swagger.v3.oas.annotations.media.Schema
import java.time.Instant
import java.time.OffsetDateTime

data class ServiceRequestResponse(
    @field:Schema(description = "의뢰 식별자") val requestId: String,
    @field:Schema(description = "원본 접수 식별자. 원본 열람은 접수 소유 권한이 별도 필요") val receptionId: String,
    @field:Schema(description = "의뢰 상태") val requestStatus: RequestStatus,
    @field:Schema(description = "업무 버전") val version: Int,
    @field:Schema(description = "작업 유형") val workType: WorkType,
    @field:Schema(description = "확정 증상") val symptoms: String,
    @field:Schema(description = "확정 전체 현장 주소") val address: String,
    @field:Schema(description = "확정 방문 시작") val desiredVisitFrom: OffsetDateTime,
    @field:Schema(description = "확정 방문 종료") val desiredVisitTo: OffsetDateTime,
    @field:Schema(description = "관리자용 고객 연락처") val contactPhone: String,
    @field:Schema(description = "확정 특이사항") val notes: String?,
    @field:Schema(description = "현 회차") val currentDispatchRoundId: String?,
    @field:Schema(description = "현재 또는 최종 작업") val currentWorkId: String?,
    @field:Schema(description = "현재 미해결 후속 조치 대상") val followUpTarget: FollowUpTargetResponse?,
    @field:Schema(description = "현재 후속 조치 원인") val followUpReason: String?,
    @field:Schema(description = "의뢰 등록 시각") val createdAt: Instant,
)
