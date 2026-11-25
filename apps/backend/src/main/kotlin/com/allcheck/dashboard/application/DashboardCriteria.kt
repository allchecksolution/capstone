package com.allcheck.dashboard.application

import com.allcheck.operations.RequestStatus
import com.allcheck.operations.WorkStatus
import com.allcheck.operations.WorkType
import java.time.LocalDate

internal data class DashboardCriteria(
    val from: LocalDate?,
    val to: LocalDate?,
    val requestStatus: RequestStatus?,
    val workStatus: WorkStatus?,
    val workType: WorkType?,
    val driverId: Long?,
    val region: String?,
    val needsAdminAction: Boolean?,
    val query: String?,
)
