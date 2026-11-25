package com.allcheck.dashboard.presentation.dto.response

import com.allcheck.operations.RequestStatus
import com.allcheck.operations.RoundStatus
import com.allcheck.operations.WorkStatus
import com.allcheck.operations.WorkType
import io.swagger.v3.oas.annotations.media.Schema
import java.time.Instant
import java.time.OffsetDateTime

data class DashboardRequestItem(
    val requestId: String,
    val receptionId: String,
    val requestStatus: RequestStatus,
    val version: Int,
    val workType: WorkType,
    val symptoms: String,
    val address: String,
    val desiredVisitFrom: OffsetDateTime,
    val desiredVisitTo: OffsetDateTime,
    val contactPhone: String,
    val notes: String?,
    val currentDispatchRoundId: String?,
    val currentWorkId: String?,
    val followUpTarget: DashboardFollowUpTarget?,
    val followUpReason: String?,
    val createdAt: Instant,
    val area: String,
    val currentDispatchStatus: RoundStatus?,
    val currentWorkStatus: WorkStatus?,
    @field:Schema(description = "현재 연결된 작업의 담당 기사. 과거 작업으로 검색한 경우 검색 조건의 기사와 다를 수 있다.")
    val currentDriverId: Long?,
    @field:Schema(description = "현재 의뢰 상태가 FOLLOW_UP_REQUIRED인지 여부")
    val needsAdminAction: Boolean,
)
