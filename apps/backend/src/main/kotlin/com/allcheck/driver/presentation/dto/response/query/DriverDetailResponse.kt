package com.allcheck.driver.presentation.dto.response.query

import com.allcheck.driver.domain.vo.registration.DriverAccountStatus
import com.allcheck.driver.presentation.dto.response.registration.BusinessInfoResponse
import com.allcheck.driver.presentation.dto.response.registration.BusinessVerificationResponse
import com.allcheck.driver.presentation.dto.response.registration.DriverProfileResponse
import com.allcheck.driver.presentation.dto.response.review.RegistrationRejection
import io.swagger.v3.oas.annotations.media.Schema
import java.time.Instant

data class DriverDetailResponse(
    @field:Schema(description = "기사 식별자") val driverId: Long,
    @field:Schema(description = "가입 상태") val accountStatus: DriverAccountStatus,
    @field:Schema(description = "현재 버전") val version: Long,
    @field:Schema(description = "현재 신청 제출 시각") val submittedAt: Instant,
    @field:Schema(description = "기사 정보. 정산계좌 번호는 마스킹") val profile: DriverProfileResponse,
    @field:Schema(description = "사업자 정보") val business: BusinessInfoResponse,
    @field:Schema(description = "사업자 검증 결과") val businessVerification: BusinessVerificationResponse,
    @field:Schema(description = "재신청 전 기록을 포함한 반려 이력") val rejectionHistory: List<RegistrationRejection>,
    @field:Schema(description = "설정되지 않은 작업 가능 상태는 null") val availability: String? = null,
)
