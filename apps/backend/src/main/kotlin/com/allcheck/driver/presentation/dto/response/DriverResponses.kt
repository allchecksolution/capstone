package com.allcheck.driver.presentation.dto.response

import com.allcheck.driver.domain.vo.DriverAccountStatus
import com.allcheck.driver.presentation.dto.request.BusinessInfoRequest
import com.allcheck.driver.presentation.dto.request.DriverProfileRequest
import io.swagger.v3.oas.annotations.media.Schema
import java.time.Instant

data class DriverRegistrationResult(
    @field:Schema(description = "기사 식별자") val driverId: String,
    @field:Schema(description = "가입 상태") val accountStatus: DriverAccountStatus,
    @field:Schema(description = "현재 신청 제출 시각") val submittedAt: Instant,
    @field:Schema(description = "현재 버전") val version: Long,
)

data class RegistrationRejection(
    @field:Schema(description = "관리자가 입력한 반려 사유") val reason: String,
    @field:Schema(description = "반려 시각") val rejectedAt: Instant,
)

data class BusinessVerificationResponse(
    @field:Schema(description = "국세청 진위확인 성공 상태") val status: String = "VERIFIED",
    @field:Schema(description = "국세청 검증 시각") val verifiedAt: Instant,
)

data class DriverRegistrationView(
    @field:Schema(description = "기사 식별자") val driverId: String,
    @field:Schema(description = "가입 상태") val accountStatus: DriverAccountStatus,
    @field:Schema(description = "현재 버전") val version: Long,
    @field:Schema(description = "현재 신청 제출 시각") val submittedAt: Instant,
    @field:Schema(description = "현재 신청의 반려 사유. 반려 외에는 null") val rejection: RegistrationRejection?,
    @field:Schema(description = "본인이 제출한 개인정보") val profile: DriverProfileRequest,
    @field:Schema(description = "제출한 사업자 정보") val business: BusinessInfoRequest,
    @field:Schema(description = "사업자 검증 결과") val businessVerification: BusinessVerificationResponse,
)

data class DriverSummary(
    @field:Schema(description = "기사 식별자") val driverId: String,
    @field:Schema(description = "근로자명") val workerName: String,
    @field:Schema(description = "사업체명") val businessName: String,
    @field:Schema(description = "가입 상태") val accountStatus: DriverAccountStatus,
    @field:Schema(description = "현재 신청 제출 시각") val submittedAt: Instant,
    @field:Schema(description = "현재 버전") val version: Long,
)

data class DriverPageInfo(
    @field:Schema(description = "1부터 시작하는 페이지") val page: Int,
    @field:Schema(description = "페이지 크기") val size: Int,
    @field:Schema(description = "전체 기사 수") val totalElements: Long,
    @field:Schema(description = "전체 페이지 수") val totalPages: Int,
)

data class DriverListResponse(
    @field:Schema(description = "기사 목록") val items: List<DriverSummary>,
    @field:Schema(description = "페이지 정보") val page: DriverPageInfo,
)

data class DriverDetailResponse(
    @field:Schema(description = "기사 식별자") val driverId: String,
    @field:Schema(description = "가입 상태") val accountStatus: DriverAccountStatus,
    @field:Schema(description = "현재 버전") val version: Long,
    @field:Schema(description = "현재 신청 제출 시각") val submittedAt: Instant,
    @field:Schema(description = "기사 정보. 정산계좌 번호는 마스킹") val profile: DriverProfileRequest,
    @field:Schema(description = "사업자 정보") val business: BusinessInfoRequest,
    @field:Schema(description = "사업자 검증 결과") val businessVerification: BusinessVerificationResponse,
    @field:Schema(description = "재신청 전 기록을 포함한 반려 이력") val rejectionHistory: List<RegistrationRejection>,
    @field:Schema(description = "설정되지 않은 작업 가능 상태는 null") val availability: String? = null,
)

data class DriverApprovalResult(
    @field:Schema(description = "기사 식별자") val driverId: String,
    @field:Schema(description = "심사 후 가입 상태") val accountStatus: DriverAccountStatus,
    @field:Schema(description = "현재 버전") val version: Long,
    @field:Schema(description = "심사 시각") val decidedAt: Instant,
)

data class DriverWithdrawalResult(
    @field:Schema(description = "기사 식별자") val driverId: String,
    @field:Schema(description = "탈퇴 상태") val accountStatus: DriverAccountStatus,
    @field:Schema(description = "탈퇴 시각") val withdrawnAt: Instant,
)

data class DriverCommandResult<T>(val body: T, val replayed: Boolean)
