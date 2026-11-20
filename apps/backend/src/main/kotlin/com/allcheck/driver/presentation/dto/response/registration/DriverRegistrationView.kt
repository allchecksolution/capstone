package com.allcheck.driver.presentation.dto.response.registration

import com.allcheck.driver.domain.vo.registration.DriverAccountStatus
import com.allcheck.driver.presentation.dto.response.registration.BusinessInfoResponse
import com.allcheck.driver.presentation.dto.response.registration.DriverProfileResponse
import com.allcheck.driver.presentation.dto.response.review.RegistrationRejection
import io.swagger.v3.oas.annotations.media.Schema
import java.time.Instant

data class DriverRegistrationView(
    @field:Schema(description = "기사 식별자") val driverId: Long,
    @field:Schema(description = "가입 상태") val accountStatus: DriverAccountStatus,
    @field:Schema(description = "현재 버전") val version: Long,
    @field:Schema(description = "현재 신청 제출 시각") val submittedAt: Instant,
    @field:Schema(description = "현재 신청의 반려 사유. 반려 외에는 null") val rejection: RegistrationRejection?,
    @field:Schema(description = "본인이 제출한 개인정보") val profile: DriverProfileResponse,
    @field:Schema(description = "제출한 사업자 정보") val business: BusinessInfoResponse,
    @field:Schema(description = "사업자 검증 결과") val businessVerification: BusinessVerificationResponse,
)
