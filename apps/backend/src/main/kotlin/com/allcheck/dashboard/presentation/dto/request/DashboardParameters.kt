package com.allcheck.dashboard.presentation.dto.request

import com.allcheck.operations.RequestStatus
import com.allcheck.operations.WorkStatus
import com.allcheck.operations.WorkType
import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.constraints.Positive
import jakarta.validation.constraints.Size

data class DashboardParameters(
    @field:Schema(description = "기존 의뢰 상태 필터. requestStatus와 같이 쓰면 값이 같아야 한다.") val status: RequestStatus? = null,
    val requestStatus: RequestStatus? = null,
    val workStatus: WorkStatus? = null,
    val workType: WorkType? = null,
    @field:Positive val driverId: Long? = null,
    @field:Size(min = 1, max = 100) val region: String? = null,
    val needsAdminAction: Boolean? = null,
    @field:Size(min = 1, max = 100) val q: String? = null,
)
