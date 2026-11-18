package com.allcheck.identity.presentation.dto.request

import com.fasterxml.jackson.annotation.JsonAnySetter
import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size

@Schema(additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
class AdminLoginRequest(
    @field:NotBlank
    @field:Size(max = 100)
    @field:Schema(description = "사전 발급된 관리자 아이디. 대소문자·공백을 자동 변환하지 않음")
    val loginId: String,
    @field:NotBlank
    @field:Size(max = 200)
    @field:Schema(description = "관리자 비밀번호", accessMode = Schema.AccessMode.WRITE_ONLY)
    val password: String,
) {
    @JsonAnySetter
    @Suppress("UNUSED_PARAMETER")
    fun rejectUnknownField(field: String, value: Any?): Nothing = throw IllegalArgumentException("Unknown login request fields are not supported.")
}
