package com.allcheck.identity.presentation.dto.request

import com.fasterxml.jackson.annotation.JsonAnySetter
import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Pattern
import jakarta.validation.constraints.Size

@Schema(additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
class RefreshTokenRequest(
    @field:NotBlank
    @field:Size(max = 4000)
    @field:Pattern(regexp = "[!-~]+")
    @field:Schema(description = "서비스 로그인 또는 직전 갱신에서 받은 Refresh Token", accessMode = Schema.AccessMode.WRITE_ONLY)
    val refreshToken: String,
) {
    @JsonAnySetter
    @Suppress("UNUSED_PARAMETER")
    fun rejectUnknownField(field: String, value: Any?): Nothing = throw IllegalArgumentException("Unknown refresh token request fields are not supported.")
}
