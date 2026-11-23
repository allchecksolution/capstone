package com.allcheck.chat.presentation.dto.response

import io.swagger.v3.oas.annotations.media.Schema

internal data class ChatMessagePageResponse(
    val items: List<ChatMessageResponse>,
    @field:Schema(description = "현재 조회 방향의 다음 커서. 더 없으면 null.") val nextCursor: String?,
    val readOnly: Boolean,
)
