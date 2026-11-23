package com.allcheck.chat.presentation.dto.response

import io.swagger.v3.oas.annotations.media.Schema

internal data class ChatMessagePageResponse(
    val items: List<ChatMessageResponse>,
    @field:Schema(description = "현재 조회 방향의 다음 커서. 더 없으면 null.") val nextCursor: String?,
    @field:Schema(description = "이력 열람만 가능하고 메시지 전송과 읽음 갱신이 차단되는지 여부")
    val readOnly: Boolean,
)
