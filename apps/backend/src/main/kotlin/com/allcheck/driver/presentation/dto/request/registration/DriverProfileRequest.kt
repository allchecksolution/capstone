package com.allcheck.driver.presentation.dto.request.registration

import com.allcheck.driver.domain.vo.registration.WorkType
import com.allcheck.driver.presentation.dto.request.StrictDriverRequest
import com.fasterxml.jackson.annotation.JsonIgnore
import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.Valid
import jakarta.validation.constraints.AssertTrue
import jakarta.validation.constraints.Email
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.Pattern
import jakarta.validation.constraints.Size

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
