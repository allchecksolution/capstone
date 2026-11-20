package com.allcheck.driver.presentation.dto.response.review

import com.allcheck.driver.domain.vo.registration.DriverAccountStatus
import io.swagger.v3.oas.annotations.media.Schema
import java.time.Instant

data class DriverApprovalResult(
    @field:Schema(description = "기사 식별자") val driverId: Long,
    @field:Schema(description = "심사 후 가입 상태") val accountStatus: DriverAccountStatus,
    @field:Schema(description = "현재 버전") val version: Long,
    @field:Schema(description = "심사 시각") val decidedAt: Instant,
)
