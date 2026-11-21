package com.allcheck.chat.application

import com.allcheck.global.exception.ErrorCode
import org.springframework.http.HttpStatus
import java.time.Instant

internal enum class ChatSide {
    ADMIN,
    DRIVER,
    ;

    fun opposite(): ChatSide = if (this == ADMIN) DRIVER else ADMIN
}

internal data class ChatActor(val accountId: Long, val side: ChatSide)

internal data class Conversation(val id: Long, val driverId: Long, val adminRead: ChatReadState?, val driverRead: ChatReadState?) {
    fun readState(side: ChatSide) = if (side == ChatSide.ADMIN) adminRead else driverRead
}

internal data class ChatDriver(val id: Long, val accountId: Long, val name: String, val status: String) {
    val isActive: Boolean get() = status == "ACTIVATED"
}

internal data class ChatMessage(val id: Long, val driverId: Long, val sender: ChatSide, val body: String, val sentAt: Instant, val readAt: Instant?)

internal data class ChatPage(val messages: List<ChatMessage>, val nextId: Long?, val readOnly: Boolean)

internal data class ChatRoom(val driver: ChatDriver, val last: ChatMessage, val unreadCount: Long)

internal data class ChatRoomPage(val rooms: List<ChatRoom>, val hasNext: Boolean)

internal data class ChatReadState(val messageId: Long, val readAt: Instant)

/** Durable event carries only resource IDs. Consumers resolve current permissions at delivery. */
internal data class ChatMessageEvent(val messageId: Long, val driverId: Long, val sender: ChatSide, val read: Boolean)

internal data class ChatPushDelivery(
    val id: Long,
    val messageId: Long,
    val driverId: Long,
    val recipientSide: ChatSide,
    val accountId: Long,
    val installationId: String,
    val attempt: Int,
)

internal enum class ChatError(override val status: HttpStatus, override val message: String) : ErrorCode {
    CHAT_NOT_FOUND(HttpStatus.NOT_FOUND, "채팅방을 찾을 수 없습니다."),
    CHAT_NOT_ALLOWED(HttpStatus.FORBIDDEN, "현재 채팅을 이용할 수 없습니다."),
}
