package com.allcheck.driver.presentation.dto.response.registration

import io.swagger.v3.oas.annotations.media.Schema
import java.time.LocalDate

data class BusinessInfoResponse(
    @field:Schema(description = "사업체명") val businessName: String,
    @field:Schema(description = "사업자등록번호") val businessRegistrationNumber: String,
    @field:Schema(description = "개업일자") val openedOn: LocalDate,
    @field:Schema(description = "업태") val businessType: String,
    @field:Schema(description = "종목") val businessItem: String,
    @field:Schema(description = "대표자명") val representativeName: String,
    @field:Schema(description = "회사 대표번호") val companyPhone: String,
    @field:Schema(description = "회사 주소") val companyAddress: String,
)
