package com.allcheck.identity.presentation.dto.request.device

import com.fasterxml.jackson.annotation.JsonAnySetter
import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Pattern
import jakarta.validation.constraints.Size

enum class PushPlatform { ANDROID, IOS }

@Schema(additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
class PushDeviceRequest(
    @field:Schema(description = "앱 플랫폼") val platform: PushPlatform,
    @field:NotBlank @field:Size(max = 4000) @field:Pattern(regexp = "[!-~]+")
    @field:Schema(description = "FCM 등록 토큰. 응답·로그에 노출하지 않음", accessMode = Schema.AccessMode.WRITE_ONLY)
    val pushToken: String,
) {
    @JsonAnySetter
    @Suppress("UNUSED_PARAMETER")
    fun rejectUnknownField(field: String, value: Any?): Nothing = throw IllegalArgumentException("Unknown push device fields are not supported.")
}
