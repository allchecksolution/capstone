package com.allcheck.chat.application

import com.allcheck.chat.domain.vo.ChatSide
import java.time.Instant

internal data class ChatMessageResult(
    val id: Long,
    val driverId: Long,
    val sender: ChatSide,
    val body: String,
    val sentAt: Instant,
    val readAt: Instant?,
)

internal data class ChatPage(val messages: List<ChatMessageResult>, val nextId: Long?, val readOnly: Boolean)

internal data class ChatRoom(
    val driverId: Long,
    val driverName: String,
    val driverStatus: String,
    val last: ChatMessageResult,
    val unreadCount: Long,
    val readOnly: Boolean,
)

internal data class ChatRoomPage(val rooms: List<ChatRoom>, val hasNext: Boolean)
