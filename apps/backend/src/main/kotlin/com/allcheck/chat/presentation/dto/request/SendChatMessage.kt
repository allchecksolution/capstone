package com.allcheck.chat.presentation.dto.request

import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.constraints.NotBlank

internal data class SendChatMessage(
    @field:NotBlank
    @field:Schema(maxLength = 2000, description = "최대 2,000 유니코드 코드 포인트")
    val body: String,
)
