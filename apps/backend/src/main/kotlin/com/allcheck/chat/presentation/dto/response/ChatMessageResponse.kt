package com.allcheck.chat.presentation.dto.response

import com.allcheck.chat.application.ChatSide
import java.time.Instant

internal data class ChatMessageResponse(
    val messageId: String,
    val driverId: Long,
    val sender: ChatSide,
    val body: String,
    val sentAt: Instant,
    val readAt: Instant?,
)
