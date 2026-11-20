package com.allcheck.driver.presentation.dto.request.registration

import com.allcheck.driver.presentation.dto.request.StrictDriverRequest
import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.Valid

@Schema(additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
data class DriverRegistrationRequest(
    @field:Valid @field:Schema(description = "기사 개인정보") val profile: DriverProfileRequest,
    @field:Valid @field:Schema(description = "사업자 정보") val business: BusinessInfoRequest,
) : StrictDriverRequest()
