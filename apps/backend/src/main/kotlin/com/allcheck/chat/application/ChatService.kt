package com.allcheck.chat.application

import com.allcheck.chat.domain.entity.ChatMessage
import com.allcheck.chat.domain.vo.ChatActor
import com.allcheck.chat.domain.vo.ChatReadState
import com.allcheck.chat.domain.vo.ChatSide
import com.allcheck.chat.exception.ChatErrorCode
import com.allcheck.chat.infrastructure.ChatDriverRecord
import com.allcheck.chat.infrastructure.ChatEventRepository
import com.allcheck.chat.infrastructure.ChatRepository
import com.allcheck.driver.DriverOperations
import com.allcheck.global.exception.BusinessException
import com.allcheck.global.exception.GlobalErrorCode
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.Instant

@Service
@Transactional(readOnly = true)
internal class ChatService(
    private val repository: ChatRepository,
    private val events: ChatEventRepository,
    private val drivers: DriverOperations,
    private val clock: Clock,
) {
    fun ownDriverId(actor: ChatActor): Long {
        if (actor.side != ChatSide.DRIVER) notAllowed()
        return drivers.findActiveDriverByAccountId(actor.accountId)?.driverId ?: notAllowed()
    }

    fun rooms(actor: ChatActor, search: String?, page: Int, size: Int): ChatRoomPage {
        if (actor.side != ChatSide.ADMIN) notAllowed()
        requireInput(search?.isNotBlank() != false)
        val rooms = repository.rooms(search?.trim(), (page - 1) * size, size + 1)
        val visibleRooms = rooms.take(size).map {
            ChatRoom(
                driverId = it.driver.id,
                driverName = it.driver.name,
                driverStatus = it.driver.status,
                last = it.last.toResult(),
                unreadCount = it.unreadCount,
                readOnly = !it.driver.isActive,
            )
        }
        return ChatRoomPage(visibleRooms, rooms.size > size)
    }

    fun messages(actor: ChatActor, driverId: Long, before: Long?, after: Long?, limit: Int): ChatPage {
        val driver = authorizedDriver(actor, driverId)
        val room = repository.conversation(driverId)
        // TODO(UC-15): Apply the approved retention period and purge method to withdrawn conversations and their events.
        if (!driver.isActive && room == null) notFound()
        requireInput(before == null || after == null)
        val cursor = before ?: after
        if (cursor != null) requireInput(room != null && repository.messageBelongsTo(room.id, cursor))
        if (room == null) return ChatPage(emptyList(), null, false)
        val rows = repository.messages(room, before, after, limit + 1)
        return ChatPage(rows.take(limit).map { it.toResult() }, if (rows.size > limit) rows[limit - 1].id else null, !driver.isActive)
    }

    @Transactional
    fun send(actor: ChatActor, driverId: Long, key: String, body: String): ChatMessageResult {
        requireInput(key.isNotBlank() && '\u0000' !in key)
        ChatMessage.validateBody(body)
        requireActiveForWrite(actor, driverId)
        val now = clock.instant()
        repository.createConversation(driverId, now)
        val room = checkNotNull(repository.lockConversation(driverId))
        val previous = repository.sentWithKey(room, actor.accountId, key)
        if (previous != null) {
            if (previous.body != body) throw BusinessException(GlobalErrorCode.IDEMPOTENCY_KEY_REUSED)
            return previous.toResult(readAt = null)
        }
        val message = repository.append(room, actor, key, body, now)
        events.enqueue(message.id)
        return message.toResult()
    }

    @Transactional
    fun markRead(actor: ChatActor, driverId: Long, messageId: Long): ChatReadState {
        requireActiveForWrite(actor, driverId)
        val room = repository.lockConversation(driverId) ?: notFound()
        requireInput(repository.messageBelongsTo(room.id, messageId))
        val next = room.readThrough(actor.side, messageId, clock.instant())
        if (next == room.readState(actor.side)) return next
        return repository.saveReadState(room.id, actor.side, next)
    }

    fun canSubscribe(actor: ChatActor, driverId: Long): Boolean {
        val driver = drivers.findActiveDriverById(driverId) ?: return false
        return actor.side == ChatSide.ADMIN || driver.accountId == actor.accountId
    }

    private fun authorizedDriver(actor: ChatActor, driverId: Long): ChatDriverRecord {
        val driver = repository.driver(driverId) ?: notFound()
        if (actor.side == ChatSide.DRIVER && (driver.accountId != actor.accountId || !driver.isActive)) notAllowed()
        return driver
    }

    private fun requireActiveForWrite(actor: ChatActor, driverId: Long) {
        val driver = authorizedDriver(actor, driverId)
        if (!driver.isActive) notAllowed()
        try {
            // Serialize sends/reads with withdrawal using the same account → driver lock order.
            drivers.lockActiveDriver(driverId)
        } catch (failure: BusinessException) {
            if (failure.errorCode.status in setOf(HttpStatus.UNAUTHORIZED, HttpStatus.FORBIDDEN, HttpStatus.NOT_FOUND)) notAllowed()
            throw failure
        }
    }

    private fun requireInput(valid: Boolean) {
        if (!valid) throw BusinessException(GlobalErrorCode.INVALID_INPUT_VALUE)
    }

    private fun ChatMessage.toResult(readAt: Instant? = this.readAt) = ChatMessageResult(id, driverId, sender, body, sentAt, readAt)

    private fun notAllowed(): Nothing = throw BusinessException(ChatErrorCode.CHAT_NOT_ALLOWED)
    private fun notFound(): Nothing = throw BusinessException(ChatErrorCode.CHAT_NOT_FOUND)
}
