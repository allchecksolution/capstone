package com.allcheck.chat.presentation.dto.response

internal data class ChatRoomResponse(
    val driver: ChatRoomDriverResponse,
    val driverAccountStatus: String,
    val lastMessage: ChatMessageResponse,
    val unreadCount: Long,
    val readOnly: Boolean,
)
