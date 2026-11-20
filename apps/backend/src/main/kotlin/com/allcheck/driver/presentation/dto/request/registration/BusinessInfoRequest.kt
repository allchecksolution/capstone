package com.allcheck.driver.presentation.dto.request.registration

import com.allcheck.driver.presentation.dto.request.StrictDriverRequest
import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Pattern
import jakarta.validation.constraints.Size
import java.time.LocalDate

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
