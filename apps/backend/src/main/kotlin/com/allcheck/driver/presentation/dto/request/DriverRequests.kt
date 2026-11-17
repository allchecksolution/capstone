package com.allcheck.driver.presentation.dto.request

import com.allcheck.driver.domain.vo.WorkType
import com.fasterxml.jackson.annotation.JsonAnySetter
import com.fasterxml.jackson.annotation.JsonIgnore
import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.Valid
import jakarta.validation.constraints.AssertTrue
import jakarta.validation.constraints.Email
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.Pattern
import jakarta.validation.constraints.Size
import java.time.LocalDate

abstract class StrictDriverRequest {
    @JsonAnySetter
    @Suppress("UNUSED_PARAMETER")
    fun rejectUnknownField(field: String, value: Any?): Nothing = throw IllegalArgumentException("Unknown driver request fields are not supported.")
}

@Schema(additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
data class SettlementAccountRequest(
    @field:NotBlank @field:Size(max = 50) @field:Schema(description = "은행명") val bankName: String,
    @field:Pattern(regexp = "[0-9*-]{6,30}") @field:Schema(description = "기사 개인 정산계좌 번호") val accountNumber: String,
    @field:NotBlank @field:Size(max = 50) @field:Schema(description = "개인 예금주명") val holderName: String,
) : StrictDriverRequest()

@Schema(additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
data class DriverProfileRequest(
    @field:NotBlank @field:Size(max = 100) @field:Schema(description = "근로자명") val workerName: String,
    @field:Pattern(regexp = "[0-9+-]{9,20}") @field:Schema(description = "기사 전화번호") val phone: String,
    @field:NotBlank @field:Email @field:Size(max = 254) @field:Schema(description = "기사 이메일") val email: String,
    @field:NotBlank @field:Size(max = 200) @field:Schema(description = "기사 거주지") val residence: String,
    @field:NotBlank @field:Size(max = 20) @field:Schema(description = "차량번호") val vehicleNumber: String,
    @field:Valid @field:Schema(description = "기사 개인 명의 정산계좌") val settlementAccount: SettlementAccountRequest,
    @field:NotBlank @field:Email @field:Size(max = 254) @field:Schema(description = "개인 세금계산서 이메일") val taxInvoiceEmail: String,
    @field:Size(min = 1, max = 3) @field:Schema(description = "전문 분야") val specialties: List<@NotNull WorkType>,
    @field:Size(min = 1, max = 20) @field:Schema(description = "활동 지역") val activityRegions: List<
        @NotBlank
        @Size(max = 100)
        String,
        >,
    @field:Size(max = 50) @field:Schema(description = "보유 장비") val equipment: List<
        @NotBlank
        @Size(max = 100)
        String,
        >,
) : StrictDriverRequest() {
    @AssertTrue(message = "전문 분야·활동 지역·장비에는 중복 값을 넣을 수 없습니다.")
    @JsonIgnore
    fun isDistinctCollections(): Boolean = specialties.distinct().size == specialties.size &&
        activityRegions.distinct().size == activityRegions.size &&
        equipment.distinct().size == equipment.size
}

@Schema(additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
data class BusinessInfoRequest(
    @field:NotBlank @field:Size(max = 100) @field:Schema(description = "사업체명") val businessName: String,
    @field:Pattern(regexp = "[0-9]{10}") @field:Schema(description = "사업자등록번호") val businessRegistrationNumber: String,
    @field:Schema(description = "사업자등록증의 개업일자") val openedOn: LocalDate,
    @field:NotBlank @field:Size(max = 100) @field:Schema(description = "업태") val businessType: String,
    @field:NotBlank @field:Size(max = 100) @field:Schema(description = "종목") val businessItem: String,
    @field:NotBlank @field:Size(max = 100) @field:Schema(description = "대표자명") val representativeName: String,
    @field:Pattern(regexp = "[0-9+-]{9,20}") @field:Schema(description = "회사 대표번호") val companyPhone: String,
    @field:NotBlank @field:Size(max = 200) @field:Schema(description = "회사 주소") val companyAddress: String,
) : StrictDriverRequest()

@Schema(additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
data class DriverRegistrationRequest(
    @field:Valid @field:Schema(description = "기사 개인정보") val profile: DriverProfileRequest,
    @field:Valid @field:Schema(description = "사업자 정보") val business: BusinessInfoRequest,
) : StrictDriverRequest()

@Schema(additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
data class DriverResubmissionRequest(
    @field:Min(1) @field:Schema(description = "조회한 현재 버전") val expectedVersion: Long,
    @field:Valid @field:Schema(description = "수정한 가입 신청") val registration: DriverRegistrationRequest,
) : StrictDriverRequest()

enum class ApprovalDecision { APPROVE, REJECT }

@Schema(additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
data class DriverApprovalRequest(
    @field:Min(1) @field:Schema(description = "조회한 현재 버전") val expectedVersion: Long,
    @field:Schema(description = "심사 결정") val decision: ApprovalDecision,
    @field:Size(min = 1, max = 1000) @field:Schema(description = "반려 시 필수 사유") val rejectionReason: String? = null,
) : StrictDriverRequest() {
    @AssertTrue(message = "반려 시 사유를 입력해야 합니다.")
    @JsonIgnore
    fun isRejectionReasonPresent(): Boolean = decision != ApprovalDecision.REJECT || !rejectionReason.isNullOrBlank()
}

@Schema(additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
data class DriverWithdrawalRequest(
    @field:Min(1) @field:Schema(description = "조회한 현재 버전") val expectedVersion: Long,
    @field:AssertTrue @field:Schema(description = "탈퇴 유의사항 최종 확인") val confirmed: Boolean,
) : StrictDriverRequest()
