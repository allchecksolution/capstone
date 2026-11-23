package com.allcheck.chat.presentation.dto.response

import com.allcheck.chat.domain.vo.ChatSide
import io.swagger.v3.oas.annotations.media.Schema
import java.time.Instant

internal data class ChatMessageResponse(
    val messageId: String,
    val driverId: Long,
    val sender: ChatSide,
    val body: String,
    val sentAt: Instant,
    @field:Schema(description = "이력 조회 시 상대 측이 처음 읽은 시각. 읽기 전 또는 전송 응답에서는 null.")
    val readAt: Instant?,
)
