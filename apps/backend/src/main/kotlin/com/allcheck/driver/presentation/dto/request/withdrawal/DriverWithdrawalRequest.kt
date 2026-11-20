package com.allcheck.driver.presentation.dto.request.withdrawal

import com.allcheck.driver.presentation.dto.request.StrictDriverRequest
import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.constraints.AssertTrue
import jakarta.validation.constraints.Min

@Schema(additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
data class DriverWithdrawalRequest(
    @field:Min(1) @field:Schema(description = "조회한 현재 버전") val expectedVersion: Long,
    @field:AssertTrue @field:Schema(description = "탈퇴 유의사항 최종 확인") val confirmed: Boolean,
) : StrictDriverRequest()
