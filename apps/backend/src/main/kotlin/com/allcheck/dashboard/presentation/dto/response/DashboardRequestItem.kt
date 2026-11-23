package com.allcheck.dashboard.presentation.dto.response

import com.allcheck.operations.RequestStatus
import com.allcheck.operations.RoundStatus
import com.allcheck.operations.WorkStatus
import com.allcheck.operations.WorkType
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
    val currentDriverId: Long?,
    val needsAdminAction: Boolean,
)
