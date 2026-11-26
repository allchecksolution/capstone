package com.allcheck.chat.infrastructure

import com.allcheck.chat.domain.entity.ChatMessage
import com.allcheck.chat.domain.entity.Conversation
import com.allcheck.chat.domain.vo.ChatActor
import com.allcheck.chat.domain.vo.ChatReadState
import com.allcheck.chat.domain.vo.ChatSide
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import java.sql.ResultSet
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import kotlin.jvm.optionals.getOrNull

@Repository
internal class ChatRepository(private val jdbc: JdbcClient) {

    fun driver(driverId: Long) = jdbc.sql("SELECT * FROM driver_chat_directory WHERE driver_id = :driverId")
        .param("driverId", driverId).query { row, _ -> row.driver() }.optional().getOrNull()

    fun conversation(driverId: Long): Conversation? = conversation(driverId, lock = false)

    fun lockConversation(driverId: Long): Conversation? = conversation(driverId, lock = true)

    private fun conversation(driverId: Long, lock: Boolean): Conversation? {
        val clause = if (lock) " FOR UPDATE" else ""
        return jdbc.sql("SELECT * FROM chat_conversations WHERE driver_id = :driverId$clause")
            .param("driverId", driverId).query { row, _ ->
                Conversation(row.getLong("conversation_id"), driverId, row.readState("admin"), row.readState("driver"))
            }.optional().getOrNull()
    }

    fun createConversation(driverId: Long, now: Instant) {
        jdbc.sql(
            """
            INSERT INTO chat_conversations(driver_id, created_at) VALUES (:driverId, :now)
            ON CONFLICT (driver_id) DO NOTHING
            """.trimIndent(),
        ).param("driverId", driverId).param("now", now.atOffset(ZoneOffset.UTC)).update()
    }

    fun rooms(search: String?, offset: Int, limit: Int): List<ChatRoomRecord> {
        val searchClause = if (search == null) "" else "WHERE strpos(lower(d.worker_name), lower(:search)) > 0"
        val query = jdbc.sql(
            """
            SELECT d.*, last.message_id, last.sender, last.body, last.sent_at, last.read_at,
                (SELECT count(*) FROM chat_messages unread
                 WHERE unread.conversation_id = c.conversation_id AND unread.sender = 'DRIVER' AND unread.read_at IS NULL) AS unread_count
            FROM chat_conversations c
            JOIN driver_chat_directory d ON d.driver_id = c.driver_id
            JOIN LATERAL (
                SELECT m.* FROM chat_messages m WHERE m.conversation_id = c.conversation_id
                ORDER BY m.message_id DESC LIMIT 1
            ) last ON true
            $searchClause
            ORDER BY last.message_id DESC
            LIMIT :limit OFFSET :offset
            """.trimIndent(),
        ).param("limit", limit).param("offset", offset)
        search?.let { query.param("search", it) }
        return query.query { row, _ ->
            val driver = row.driver()
            ChatRoomRecord(driver, row.message(driver.id), row.getLong("unread_count"))
        }.list()
    }

    fun messages(room: Conversation, before: Long?, after: Long?, limit: Int): List<ChatMessage> {
        val comparison = when {
            after != null -> "AND message_id > :cursor"
            before != null -> "AND message_id < :cursor"
            else -> ""
        }
        val order = if (after == null) "DESC" else "ASC"
        val query = jdbc.sql(
            """
            SELECT message_id, sender, body, sent_at, read_at FROM chat_messages
            WHERE conversation_id = :room $comparison
            ORDER BY message_id $order LIMIT :limit
            """.trimIndent(),
        ).param("room", room.id).param("limit", limit)
        (before ?: after)?.let { query.param("cursor", it) }
        return query.query { row, _ -> row.message(room.driverId) }.list()
    }

    fun messageBelongsTo(roomId: Long, messageId: Long): Boolean = jdbc.sql(
        "SELECT EXISTS(SELECT 1 FROM chat_messages WHERE conversation_id = :room AND message_id = :message)",
    ).param("room", roomId).param("message", messageId).query(Boolean::class.java).single()

    fun sentWithKey(room: Conversation, accountId: Long, key: String): ChatMessage? = jdbc.sql(
        """
        SELECT message_id, sender, body, sent_at, read_at FROM chat_messages
        WHERE conversation_id = :room AND sender_account_id = :account AND idempotency_key = :key
        """.trimIndent(),
    ).param("room", room.id).param("account", accountId).param("key", key)
        .query { row, _ -> row.message(room.driverId) }.optional().getOrNull()

    fun append(room: Conversation, actor: ChatActor, key: String, body: String, now: Instant): ChatMessage = jdbc.sql(
        """
        INSERT INTO chat_messages(conversation_id, sender_account_id, sender, idempotency_key, body, sent_at)
        VALUES (:room, :account, :sender, :key, :body, :now)
        RETURNING message_id, sender, body, sent_at, read_at
        """.trimIndent(),
    ).param("room", room.id).param("account", actor.accountId).param("sender", actor.side.name)
        .param("key", key).param("body", body).param("now", now.atOffset(ZoneOffset.UTC))
        .query { row, _ -> row.message(room.driverId) }.single()

    fun saveReadState(roomId: Long, reader: ChatSide, state: ChatReadState): ChatReadState {
        val columns = if (reader == ChatSide.ADMIN) "admin" else "driver"
        val updated = jdbc.sql(
            """
            UPDATE chat_conversations SET ${columns}_read_message_id = :through, ${columns}_read_at = :now
            WHERE conversation_id = :room
            RETURNING ${columns}_read_message_id, ${columns}_read_at
            """.trimIndent(),
        ).param("room", roomId).param("through", state.messageId).param("now", state.readAt.atOffset(ZoneOffset.UTC))
            .query { row, _ -> checkNotNull(row.readState(columns)) }.single()
        jdbc.sql(
            """
            UPDATE chat_messages SET read_at = :now
            WHERE conversation_id = :room AND sender = :incoming AND read_at IS NULL AND message_id <= :through
            """.trimIndent(),
        ).param("room", roomId).param("incoming", reader.opposite().name)
            .param("through", state.messageId).param("now", updated.readAt.atOffset(ZoneOffset.UTC)).update()
        return updated
    }

    private fun ResultSet.driver() = ChatDriverRecord(getLong("driver_id"), getLong("account_id"), getString("worker_name"), getString("account_status"))

    private fun ResultSet.readState(side: String): ChatReadState? = instant("${side}_read_at")?.let {
        ChatReadState(getLong("${side}_read_message_id"), it)
    }

    private fun ResultSet.message(driverId: Long) = ChatMessage(
        getLong("message_id"),
        driverId,
        ChatSide.valueOf(getString("sender")),
        getString("body"),
        checkNotNull(instant("sent_at")),
        instant("read_at"),
    )
}

internal fun ResultSet.instant(column: String): Instant? = getObject(column, OffsetDateTime::class.java)?.toInstant()
