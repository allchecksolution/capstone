package com.allcheck.chat.presentation.dto.response

import com.allcheck.chat.application.ChatMessage
import com.allcheck.chat.application.ChatPage
import com.allcheck.chat.application.ChatReadState
import com.allcheck.chat.application.ChatRoom
import com.allcheck.chat.presentation.chatMessageId

internal fun ChatMessage.response() = ChatMessageResponse(chatMessageId(id), driverId, sender, body, sentAt, readAt)

internal fun ChatPage.response() = ChatMessagePageResponse(messages.map { it.response() }, nextId?.let(::chatMessageId), readOnly)

internal fun ChatRoom.response() = ChatRoomResponse(ChatRoomDriverResponse(driver.id, driver.name), driver.status, last.response(), unreadCount, !driver.isActive)

internal fun ChatReadState.response() = ChatReadResponse(chatMessageId(messageId), readAt)
