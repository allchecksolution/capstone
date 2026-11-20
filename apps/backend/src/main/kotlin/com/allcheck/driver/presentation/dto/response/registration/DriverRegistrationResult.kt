package com.allcheck.driver.presentation.dto.response.registration

import com.allcheck.driver.domain.vo.registration.DriverAccountStatus
import com.allcheck.driver.presentation.dto.response.registration.BusinessInfoResponse
import com.allcheck.driver.presentation.dto.response.registration.DriverProfileResponse
import io.swagger.v3.oas.annotations.media.Schema
import java.time.Instant

data class DriverRegistrationResult(
    @field:Schema(description = "기사 식별자") val driverId: String,
    @field:Schema(description = "가입 상태") val accountStatus: DriverAccountStatus,
    @field:Schema(description = "현재 신청 제출 시각") val submittedAt: Instant,
    @field:Schema(description = "현재 버전") val version: Long,
)
