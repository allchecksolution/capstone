package com.allcheck.chat.presentation.dto.response

import com.allcheck.chat.application.ChatMessageResult
import com.allcheck.chat.application.ChatPage
import com.allcheck.chat.application.ChatRoom
import com.allcheck.chat.domain.vo.ChatReadState
import com.allcheck.chat.presentation.chatMessageId

internal fun ChatMessageResult.response() = ChatMessageResponse(chatMessageId(id), driverId, sender, body, sentAt, readAt)

internal fun ChatPage.response() = ChatMessagePageResponse(messages.map { it.response() }, nextId?.let(::chatMessageId), readOnly)

internal fun ChatRoom.response() = ChatRoomResponse(ChatRoomDriverResponse(driverId, driverName), driverStatus, last.response(), unreadCount, readOnly)

internal fun ChatReadState.response() = ChatReadResponse(chatMessageId(messageId), readAt)
