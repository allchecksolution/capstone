package com.allcheck.chat.presentation.socket

import com.allcheck.chat.application.ChatActor
import com.allcheck.chat.application.ChatMessageEvent
import com.allcheck.chat.application.ChatRealtimeDelivery
import com.allcheck.chat.application.ChatService
import com.allcheck.chat.application.ChatSide
import com.allcheck.chat.presentation.chatMessageId
import com.allcheck.identity.IdentityOperations
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.springframework.web.socket.CloseStatus
import org.springframework.web.socket.SubProtocolCapable
import org.springframework.web.socket.TextMessage
import org.springframework.web.socket.WebSocketSession
import org.springframework.web.socket.handler.ConcurrentWebSocketSessionDecorator
import org.springframework.web.socket.handler.TextWebSocketHandler
import tools.jackson.core.JacksonException
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.io.IOException
import java.time.Clock
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

/** Owns the foreground subscription for each open connection in this backend instance. */
@Component
internal class ChatSocketHandler(
    private val chat: ChatService,
    private val identity: IdentityOperations,
    private val mapper: JsonMapper,
    private val clock: Clock,
) : TextWebSocketHandler(),
    SubProtocolCapable,
    ChatRealtimeDelivery {

    private val connections = ConcurrentHashMap<String, Connection>()

    override fun getSubProtocols(): List<String> = listOf("allcheck-chat.v1")

    override fun afterConnectionEstablished(session: WebSocketSession) {
        val socketIdentity = session.attributes[IDENTITY_ATTRIBUTE] as ChatSocketIdentity
        session.textMessageSizeLimit = 2048
        connections[session.id] = Connection(
            ConcurrentWebSocketSessionDecorator(session, 5000, 32 * 1024),
            socketIdentity,
            chat.actor(socketIdentity.principal),
            clock.instant(),
        )
    }

    override fun handleTextMessage(session: WebSocketSession, message: TextMessage) {
        val connection = connections[session.id] ?: return
        if (!valid(connection)) return
        val frame = try {
            mapper.readTree(message.payload)
        } catch (_: JacksonException) {
            error(connection, "INVALID_INPUT_VALUE")
            return
        }

        when (frame.path("type").asString("")) {
            "SUBSCRIBE" -> subscribe(connection, frame.path("driverId"))
            "UNSUBSCRIBE" -> {
                connection.driverId = null
                send(connection, mapOf("type" to "UNSUBSCRIBED"))
            }
            "PING" -> send(connection, mapOf("type" to "PONG"))
            else -> error(connection, "INVALID_INPUT_VALUE")
        }
        connection.lastActivity = clock.instant()
    }

    private fun subscribe(connection: Connection, driver: JsonNode) {
        val driverId = if (driver.isIntegralNumber && driver.canConvertToLong()) driver.asLong() else 0L
        if (driverId <= 0) {
            error(connection, "INVALID_INPUT_VALUE")
            return
        }
        if (!chat.canSubscribe(connection.actor, driverId)) {
            error(connection, "CHAT_NOT_ALLOWED")
            return
        }
        connection.driverId = driverId
        send(connection, mapOf("type" to "SUBSCRIBED", "driverId" to driverId))
    }

    override fun publish(event: ChatMessageEvent): Boolean {
        val payload = mapOf(
            "type" to "MESSAGE_CREATED",
            "driverId" to event.driverId,
            "messageId" to chatMessageId(event.messageId),
            "sender" to event.sender.name,
        )
        var recipientNotified = false
        for (connection in connections.values) {
            if (connection.driverId != event.driverId || !valid(connection)) continue
            if (!chat.canSubscribe(connection.actor, event.driverId)) {
                connection.driverId = null
                continue
            }
            val sent = send(connection, payload)
            if (sent && connection.actor.side == event.sender.opposite()) recipientNotified = true
        }
        return recipientNotified
    }

    override fun hasRecipient(driverId: Long, side: ChatSide): Boolean = connections.values.any {
        it.driverId == driverId && it.actor.side == side && valid(it) && chat.canSubscribe(it.actor, driverId)
    }

    @Scheduled(fixedDelay = 15_000, scheduler = "chatDeliveryScheduler")
    fun closeStaleConnections() {
        connections.values.forEach {
            if (!valid(it)) return@forEach
            val driverId = it.driverId
            if (driverId != null && !chat.canSubscribe(it.actor, driverId)) close(it, CloseStatus.POLICY_VIOLATION)
        }
    }

    override fun afterConnectionClosed(session: WebSocketSession, status: CloseStatus) {
        connections.remove(session.id)
    }

    override fun handleTransportError(session: WebSocketSession, exception: Throwable) {
        connections[session.id]?.let { close(it, CloseStatus.SERVER_ERROR) }
    }

    private fun valid(connection: Connection): Boolean {
        val now = clock.instant()
        val principal = connection.identity.principal
        val current = connection.socket.isOpen &&
            now < connection.identity.expiresAt &&
            now < connection.lastActivity.plusSeconds(60) &&
            identity.findAuthenticatedAccount(principal.accountId, principal.sessionId)?.userType == principal.userType
        if (!current) close(connection, CloseStatus.POLICY_VIOLATION)
        return current
    }

    private fun error(connection: Connection, code: String) {
        send(connection, mapOf("type" to "ERROR", "code" to code))
    }

    private fun send(connection: Connection, message: Map<String, Any>): Boolean = try {
        connection.socket.sendMessage(TextMessage(mapper.writeValueAsString(message)))
        true
    } catch (_: IOException) {
        close(connection, CloseStatus.SERVER_ERROR)
        false
    } catch (_: IllegalStateException) {
        close(connection, CloseStatus.SERVER_ERROR)
        false
    }

    private fun close(connection: Connection, status: CloseStatus) {
        if (!connections.remove(connection.socket.id, connection)) return
        try {
            connection.socket.close(status)
        } catch (_: IOException) {
            // The transport is already unusable and its subscription has been removed.
        }
    }

    private class Connection(
        val socket: WebSocketSession,
        val identity: ChatSocketIdentity,
        val actor: ChatActor,
        @Volatile var lastActivity: Instant,
        @Volatile var driverId: Long? = null,
    )

    companion object {
        const val IDENTITY_ATTRIBUTE = "chatSocketIdentity"
    }
}
