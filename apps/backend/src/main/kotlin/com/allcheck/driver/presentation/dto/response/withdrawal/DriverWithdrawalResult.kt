package com.allcheck.driver.presentation.dto.response.withdrawal

import com.allcheck.driver.domain.vo.registration.DriverAccountStatus
import io.swagger.v3.oas.annotations.media.Schema
import java.time.Instant

data class DriverWithdrawalResult(
    @field:Schema(description = "기사 식별자") val driverId: Long,
    @field:Schema(description = "탈퇴 상태") val accountStatus: DriverAccountStatus,
    @field:Schema(description = "탈퇴 시각") val withdrawnAt: Instant,
)
