package com.allcheck.operations.coordination.presentation.dto.response

import com.allcheck.operations.WorkStatus
import com.allcheck.operations.WorkType
import io.swagger.v3.oas.annotations.media.Schema
import java.time.Instant
import java.time.OffsetDateTime

data class WorkResponse(
    @field:Schema(description = "작업 식별자") val workId: String,
    @field:Schema(description = "의뢰 식별자") val requestId: String,
    @field:Schema(description = "원본 담당 기사 식별자") val driverId: String,
    @field:Schema(description = "이전 취소 작업 식별자") val previousWorkId: String?,
    @field:Schema(description = "진행 상태") val workStatus: WorkStatus,
    @field:Schema(description = "작업 업무 버전") val version: Int,
    @field:Schema(description = "작업 유형") val workType: WorkType,
    @field:Schema(description = "확정 증상") val symptoms: String,
    @field:Schema(description = "현장 주소") val address: String,
    @field:Schema(description = "현재 미완료 작업의 담당 기사 및 관리자에게만 공개하는 연락처") val contactPhone: String?,
    @field:Schema(description = "확정 방문 시작") val desiredVisitFrom: OffsetDateTime,
    @field:Schema(description = "확정 방문 종료") val desiredVisitTo: OffsetDateTime,
    @field:Schema(description = "확정 특이사항") val notes: String?,
    @field:Schema(description = "배정 시각") val assignedAt: Instant,
    @field:Schema(description = "첫 보고서 저장 시각") val completedAt: Instant?,
    @field:Schema(description = "실제 취소 시각") val cancelledAt: Instant?,
    @field:Schema(description = "보고서 식별자") val reportId: String?,
    @field:Schema(description = "취소 상세. 별도 조회 없이 사유 표시 가능") val cancellation: CancellationResponse?,
    @field:Schema(description = "진행과 재방문 전체 이력") val events: List<WorkEventResponse>,
    @field:Schema(description = "현재 호출자의 진행 명령 권한") val canTransition: Boolean,
    @field:Schema(description = "현재 호출자의 최초 보고서 제출 가능 여부") val canSubmitReport: Boolean,
)
