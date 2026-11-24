package com.allcheck.driver.presentation.dto.response.registration

import com.allcheck.driver.presentation.dto.response.registration.BusinessInfoResponse
import com.allcheck.driver.presentation.dto.response.registration.DriverProfileResponse
import io.swagger.v3.oas.annotations.media.Schema
import java.time.Instant

data class BusinessVerificationResponse(
    @field:Schema(description = "국세청 진위확인 성공 상태") val status: String = "VERIFIED",
    @field:Schema(description = "국세청 검증 시각") val verifiedAt: Instant,
)
