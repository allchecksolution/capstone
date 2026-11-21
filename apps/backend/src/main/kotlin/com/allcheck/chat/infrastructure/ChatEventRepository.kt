package com.allcheck.chat.infrastructure

import com.allcheck.chat.application.ChatMessageEvent
import com.allcheck.chat.application.ChatPushDelivery
import com.allcheck.chat.application.ChatSide
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import java.sql.ResultSet
import kotlin.jvm.optionals.getOrNull

@Repository
internal class ChatEventRepository(private val jdbc: JdbcClient) {

    fun enqueue(messageId: Long) {
        jdbc.sql("INSERT INTO chat_message_events(message_id) VALUES (:id)").param("id", messageId).update()
    }

    fun pendingEvents(limit: Int): List<ChatMessageEvent> = jdbc.sql(
        """
        SELECT m.message_id, c.driver_id, m.sender, (m.read_at IS NOT NULL) AS read
        FROM chat_message_events e
        JOIN chat_messages m ON m.message_id = e.message_id
        JOIN chat_conversations c ON c.conversation_id = m.conversation_id
        WHERE e.processed_at IS NULL
        ORDER BY m.message_id LIMIT :limit
        """.trimIndent(),
    ).param("limit", limit).query { row, _ -> row.event() }.list()

    fun isUnread(messageId: Long): Boolean = jdbc.sql(
        "SELECT EXISTS(SELECT 1 FROM chat_messages WHERE message_id = :id AND read_at IS NULL)",
    ).param("id", messageId).query(Boolean::class.java).single()

    fun completeEvent(messageId: Long): Boolean = jdbc.sql(
        "UPDATE chat_message_events SET processed_at = now() WHERE message_id = :id AND processed_at IS NULL",
    ).param("id", messageId).update() == 1

    fun enqueuePush(messageId: Long, accountId: Long, installationId: String) {
        jdbc.sql(
            """
            INSERT INTO chat_push_deliveries(message_id, account_id, installation_id, due_at)
            VALUES (:message, :account, :installation, now())
            ON CONFLICT (message_id, installation_id) DO NOTHING
            """.trimIndent(),
        ).param("message", messageId).param("account", accountId).param("installation", installationId).update()
    }

    fun claimPush(leaseSeconds: Long): ChatPushDelivery? = jdbc.sql(
        """
        WITH next_delivery AS (
            SELECT delivery_id FROM chat_push_deliveries
            WHERE status = 'PENDING' AND due_at <= now() AND (lease_until IS NULL OR lease_until <= now())
            ORDER BY due_at, delivery_id LIMIT 1 FOR UPDATE SKIP LOCKED
        ), claimed AS (
            UPDATE chat_push_deliveries p
            SET lease_until = now() + :lease * interval '1 second', attempts = attempts + 1
            FROM next_delivery n WHERE p.delivery_id = n.delivery_id
            RETURNING p.*
        )
        SELECT p.*, c.driver_id, m.sender FROM claimed p
        JOIN chat_messages m ON m.message_id = p.message_id
        JOIN chat_conversations c ON c.conversation_id = m.conversation_id
        """.trimIndent(),
    ).param("lease", leaseSeconds).query { row, _ ->
        ChatPushDelivery(
            row.getLong("delivery_id"),
            row.getLong("message_id"),
            row.getLong("driver_id"),
            ChatSide.valueOf(row.getString("sender")).opposite(),
            row.getLong("account_id"),
            row.getString("installation_id"),
            row.getInt("attempts"),
        )
    }.optional().getOrNull()

    fun hasLease(delivery: ChatPushDelivery): Boolean = jdbc.sql(
        """
        SELECT EXISTS(SELECT 1 FROM chat_push_deliveries
        WHERE delivery_id = :id AND attempts = :attempt AND lease_until > now() AND status = 'PENDING')
        """.trimIndent(),
    ).param("id", delivery.id).param("attempt", delivery.attempt).query(Boolean::class.java).single()

    fun completePush(delivery: ChatPushDelivery, status: PushDeliveryStatus, delaySeconds: Long) {
        jdbc.sql(
            """
            UPDATE chat_push_deliveries
            SET status = :status, lease_until = NULL, due_at = now() + :delay * interval '1 second'
            WHERE delivery_id = :id AND attempts = :attempt AND lease_until > now() AND status = 'PENDING'
            """.trimIndent(),
        ).param("status", status.name).param("delay", delaySeconds)
            .param("id", delivery.id).param("attempt", delivery.attempt).update()
    }

    private fun ResultSet.event() = ChatMessageEvent(getLong("message_id"), getLong("driver_id"), ChatSide.valueOf(getString("sender")), getBoolean("read"))
}

internal enum class PushDeliveryStatus { PENDING, PROVIDER_ACCEPTED, FAILED, SKIPPED }
