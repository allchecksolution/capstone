package com.allcheck.chat.presentation.dto.response

internal data class ChatRoomPageResponse(val items: List<ChatRoomResponse>, val page: Int, val size: Int, val hasNext: Boolean)
