package com.allcheck.driver.presentation.dto.request.registration

import com.allcheck.driver.presentation.dto.request.StrictDriverRequest
import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Pattern
import jakarta.validation.constraints.Size

@Schema(additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
data class SettlementAccountRequest(
    @field:NotBlank @field:Size(max = 50) @field:Schema(description = "은행명") val bankName: String,
    @field:Size(min = 6, max = 30) @field:Pattern(regexp = "[0-9]+(?:-[0-9]+)*") @field:Schema(description = "기사 개인 정산계좌 번호") val accountNumber: String,
    @field:NotBlank @field:Size(max = 50) @field:Schema(description = "개인 예금주명") val holderName: String,
) : StrictDriverRequest()
