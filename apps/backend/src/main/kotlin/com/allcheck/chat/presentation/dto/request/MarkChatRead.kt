package com.allcheck.chat.presentation.dto.request

import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size

internal data class MarkChatRead(@field:NotBlank @field:Size(max = 32) val lastReadMessageId: String)
