package com.allcheck.chat.presentation.dto

import com.allcheck.chat.application.ChatMessage
import com.allcheck.chat.application.ChatPage
import com.allcheck.chat.application.ChatReadState
import com.allcheck.chat.application.ChatRoom
import com.allcheck.chat.application.ChatSide
import com.allcheck.global.exception.BusinessException
import com.allcheck.global.exception.GlobalErrorCode
import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import java.time.Instant

internal data class SendChatMessage(
    @field:NotBlank
    @field:Schema(maxLength = 2000, description = "최대 2,000 유니코드 코드 포인트")
    val body: String,
)

internal data class MarkChatRead(@field:NotBlank @field:Size(max = 32) val lastReadMessageId: String)

internal data class ChatMessageResponse(
    val messageId: String,
    val driverId: Long,
    val sender: ChatSide,
    val body: String,
    val sentAt: Instant,
    val readAt: Instant?,
)

internal data class ChatMessagePageResponse(
    val items: List<ChatMessageResponse>,
    @field:Schema(description = "현재 조회 방향의 다음 커서. 더 없으면 null.") val nextCursor: String?,
    val readOnly: Boolean,
)

internal data class ChatReadResponse(val lastReadMessageId: String, val readAt: Instant)

internal data class ChatRoomDriverResponse(val driverId: Long, val name: String)

internal data class ChatRoomResponse(
    val driver: ChatRoomDriverResponse,
    val driverAccountStatus: String,
    val lastMessage: ChatMessageResponse,
    val unreadCount: Long,
    val readOnly: Boolean,
)

internal data class ChatRoomPageResponse(val items: List<ChatRoomResponse>, val page: Int, val size: Int, val hasNext: Boolean)

internal fun ChatMessage.response() = ChatMessageResponse(chatMessageId(id), driverId, sender, body, sentAt, readAt)

internal fun ChatPage.response() = ChatMessagePageResponse(messages.map { it.response() }, nextId?.let(::chatMessageId), readOnly)

internal fun ChatRoom.response() = ChatRoomResponse(ChatRoomDriverResponse(driver.id, driver.name), driver.status, last.response(), unreadCount, !driver.isActive)

internal fun ChatReadState.response() = ChatReadResponse(chatMessageId(messageId), readAt)

internal fun chatMessageId(id: Long) = "msg_$id"

internal fun parseChatMessageId(value: String): Long {
    if (value.startsWith("msg_")) {
        value.substring(4).toLongOrNull()?.takeIf { it > 0 && chatMessageId(it) == value }?.let { return it }
    }
    throw BusinessException(GlobalErrorCode.INVALID_INPUT_VALUE)
}
