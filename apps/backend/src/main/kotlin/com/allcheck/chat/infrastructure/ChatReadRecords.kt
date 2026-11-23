package com.allcheck.chat.infrastructure

import com.allcheck.chat.domain.entity.ChatMessage

internal data class ChatDriverRecord(val id: Long, val accountId: Long, val name: String, val status: String) {
    val isActive: Boolean get() = status == "ACTIVATED"
}

internal data class ChatRoomRecord(val driver: ChatDriverRecord, val last: ChatMessage, val unreadCount: Long)
