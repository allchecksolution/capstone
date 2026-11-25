package com.allcheck.identity.presentation.dto.request.login

import com.fasterxml.jackson.annotation.JsonAnySetter
import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Pattern
import jakarta.validation.constraints.Size

@Schema(additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
class KakaoLoginRequest(
    @field:NotBlank
    @field:Size(max = 4000)
    @field:Pattern(regexp = "[!-~]+")
    @field:Schema(description = "카카오 SDK로 발급받은 Access Token", accessMode = Schema.AccessMode.WRITE_ONLY)
    val kakaoAccessToken: String,
) {
    @JsonAnySetter
    @Suppress("UNUSED_PARAMETER")
    fun rejectUnknownField(field: String, value: Any?): Nothing = throw IllegalArgumentException("Unknown login request fields are not supported.")
}
