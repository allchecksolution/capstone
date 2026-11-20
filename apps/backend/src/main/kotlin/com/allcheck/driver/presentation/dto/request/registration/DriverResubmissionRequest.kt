package com.allcheck.driver.presentation.dto.request.registration

import com.allcheck.driver.presentation.dto.request.StrictDriverRequest
import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.Valid
import jakarta.validation.constraints.Min

@Schema(additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
data class DriverResubmissionRequest(
    @field:Min(1) @field:Schema(description = "조회한 현재 버전") val expectedVersion: Long,
    @field:Valid @field:Schema(description = "수정한 가입 신청") val registration: DriverRegistrationRequest,
) : StrictDriverRequest()
