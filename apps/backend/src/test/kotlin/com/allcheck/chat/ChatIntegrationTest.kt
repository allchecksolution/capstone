package com.allcheck.chat

import com.allcheck.chat.application.ChatActor
import com.allcheck.chat.application.ChatDeliveryWorker
import com.allcheck.chat.application.ChatError
import com.allcheck.chat.application.ChatService
import com.allcheck.chat.application.ChatSide
import com.allcheck.driver.application.withdrawal.DriverWithdrawalService
import com.allcheck.driver.application.withdrawal.WithdrawDriverCommand
import com.allcheck.global.exception.BusinessException
import com.allcheck.global.notification.FcmConnection
import com.allcheck.global.security.jwt.JwtTokenProvider
import com.allcheck.support.PostgresTestSchema
import com.allcheck.support.insertDriverFixture
import com.sun.net.httpserver.HttpServer
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.assertThrows
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.annotation.DirtiesContext
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.context.TestPropertySource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.web.client.RestClient
import tools.jackson.databind.json.JsonMapper
import java.net.InetSocketAddress
import java.net.URI
import java.net.http.HttpClient
import java.net.http.WebSocket
import java.net.http.WebSocketHandshakeException
import java.time.Duration
import java.util.concurrent.CompletionException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import javax.sql.DataSource

@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = [
        "spring.jpa.show-sql=false",
        "chat.delivery.event-poll-interval=1d",
        "chat.delivery.push-poll-interval=1d",
    ],
)
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource("classpath:jwt-test.properties")
@Import(ChatIntegrationTest.Fixtures::class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class ChatIntegrationTest {

    @Autowired
    private lateinit var chat: ChatService

    @Autowired
    private lateinit var withdrawals: DriverWithdrawalService

    @Autowired
    private lateinit var worker: ChatDeliveryWorker

    @Autowired
    private lateinit var tokens: JwtTokenProvider

    @Autowired
    private lateinit var mvc: MockMvc

    @Autowired
    private lateinit var dataSource: DataSource

    @Autowired
    private lateinit var push: PushFixture

    @LocalServerPort
    private var port: Int = 0

    private val jdbc get() = JdbcTemplate(dataSource)

    @BeforeEach
    fun reset() {
        jdbc.execute("TRUNCATE identity_accounts RESTART IDENTITY CASCADE")
        jdbc.execute("TRUNCATE driver_businesses RESTART IDENTITY CASCADE")
        jdbc.insertDriverFixture(10)
        jdbc.insertDriverFixture(20)
        jdbc.update(
            """
            INSERT INTO identity_accounts(account_id, user_type, login_id, password_hash, admin_role, created_at)
            VALUES (1, 'ADMIN', 'master', 'test', 'MASTER', now()), (2, 'ADMIN', 'sub', 'test', 'SUB', now())
            """.trimIndent(),
        )
        for (account in listOf(1L, 2L, 10L, 20L)) {
            jdbc.update(
                """
                INSERT INTO identity_auth_sessions(session_id, account_id, refresh_token_hash, created_at, expires_at)
                VALUES (?, ?, lpad(CAST(? AS text), 64, '0'), now(), now() + interval '1 hour')
                """.trimIndent(),
                account,
                account,
                account,
            )
        }
        push.received.clear()
    }

    @AfterAll
    fun cleanup() = schema.remove(dataSource)

    @Test
    fun `동시 재전송은 같은 최초 결과를 반환하고 다른 본문과 비활성 전송을 차단한다`() {
        // given
        val driver = ChatActor(10, ChatSide.DRIVER)
        assertThat(count("chat_conversations")).isZero()
        mvc.perform(get("/api/v1/chat-rooms/10/messages").header(HttpHeaders.AUTHORIZATION, bearer(2)))
            .andExpect(status().isOk).andExpect(jsonPath("$.items").isEmpty)

        // when
        val executor = Executors.newFixedThreadPool(4)
        val ids = try {
            val futures = List(4) { executor.submit<Long> { chat.send(driver, 10, "same-key", "확인했습니다").id } }
            futures.map { it.get(10, TimeUnit.SECONDS) }
        } finally {
            executor.shutdownNow()
        }

        // then
        assertThat(ids.toSet()).hasSize(1)
        assertThat(count("chat_conversations")).isEqualTo(1)
        assertThat(count("chat_messages")).isEqualTo(1)
        assertThat(count("chat_message_events")).isEqualTo(1)
        mvc.perform(send("/api/v1/drivers/me/chat/messages", 10, "same-key", "다른 내용"))
            .andExpect(status().isConflict).andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REUSED"))

        jdbc.update("UPDATE drivers SET account_status = 'WITHDRAWN', withdrawn_at = now() WHERE driver_id = 10")
        jdbc.update("UPDATE identity_accounts SET enabled = false, disabled_at = now() WHERE account_id = 10")
        mvc.perform(get("/api/v1/chat-rooms/10/messages").header(HttpHeaders.AUTHORIZATION, bearer(2)))
            .andExpect(status().isOk).andExpect(jsonPath("$.readOnly").value(true))
            .andExpect(jsonPath("$.items[0].body").value("확인했습니다"))
        mvc.perform(send("/api/v1/chat-rooms/10/messages", 2, "new", "전송 취소"))
            .andExpect(status().isForbidden)
        mvc.perform(get("/api/v1/drivers/me/chat/messages").header(HttpHeaders.AUTHORIZATION, bearer(10)))
            .andExpect(status().isUnauthorized)
        assertThat(count("chat_messages")).isEqualTo(1)
    }

    @Test
    fun `전송이 기사 탈퇴와 경합할 때 메시지와 전달 이벤트는 함께 커밋된다`() {
        // given
        val start = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(2)

        // when
        val sent = try {
            val send = executor.submit<Result<Long>> {
                start.await()
                runCatching { chat.send(ChatActor(1, ChatSide.ADMIN), 10, "racing", "전송 경합").id }
            }
            val withdraw = executor.submit {
                start.await()
                withdrawals.withdraw(10, "withdraw", WithdrawDriverCommand(1, true))
            }
            start.countDown()
            withdraw.get(10, TimeUnit.SECONDS)
            send.get(10, TimeUnit.SECONDS)
        } finally {
            executor.shutdownNow()
        }

        // then
        val expectedMessages = if (sent.isSuccess) 1L else 0L
        assertThat(count("chat_messages")).isEqualTo(expectedMessages)
        assertThat(count("chat_message_events")).isEqualTo(expectedMessages)
        sent.exceptionOrNull()?.let { assertThat((it as BusinessException).errorCode).isEqualTo(ChatError.CHAT_NOT_ALLOWED) }
        val rejected = assertThrows<BusinessException> { chat.send(ChatActor(1, ChatSide.ADMIN), 10, "after", "탈퇴 후 전송") }
        assertThat(rejected.errorCode).isEqualTo(ChatError.CHAT_NOT_ALLOWED)
    }

    @Test
    fun `권한 있는 관리자끼리 읽음 위치를 공유하고 재접속 조회와 타 대화 커서를 구분한다`() {
        // given
        val driver = ChatActor(10, ChatSide.DRIVER)
        val first = chat.send(driver, 10, "first", "첫 메시지")
        val second = chat.send(driver, 10, "second", "둘째 메시지")
        val other = chat.send(ChatActor(20, ChatSide.DRIVER), 20, "first", "별도 기사")
        jdbc.update("UPDATE drivers SET worker_name = '김가상' WHERE driver_id = 10")

        // when & then
        mvc.perform(get("/api/v1/chat-rooms").param("q", "김").header(HttpHeaders.AUTHORIZATION, bearer(2)))
            .andExpect(status().isOk).andExpect(jsonPath("$.items.length()").value(1))
            .andExpect(jsonPath("$.items[0].unreadCount").value(2))
        mvc.perform(
            put("/api/v1/chat-rooms/10/read").header(HttpHeaders.AUTHORIZATION, bearer(2))
                .contentType(MediaType.APPLICATION_JSON).content("""{"lastReadMessageId":"msg_${second.id}"}"""),
        ).andExpect(status().isOk).andExpect(jsonPath("$.lastReadMessageId").value("msg_${second.id}"))
        mvc.perform(
            put("/api/v1/chat-rooms/10/read").header(HttpHeaders.AUTHORIZATION, bearer(1))
                .contentType(MediaType.APPLICATION_JSON).content("""{"lastReadMessageId":"msg_${first.id}"}"""),
        ).andExpect(status().isOk).andExpect(jsonPath("$.lastReadMessageId").value("msg_${second.id}"))
        mvc.perform(get("/api/v1/chat-rooms").param("q", "김").header(HttpHeaders.AUTHORIZATION, bearer(1)))
            .andExpect(status().isOk).andExpect(jsonPath("$.items[0].unreadCount").value(0))
        mvc.perform(
            get("/api/v1/drivers/me/chat/messages").param("after", "msg_${first.id}").header(HttpHeaders.AUTHORIZATION, bearer(10)),
        ).andExpect(status().isOk).andExpect(jsonPath("$.items.length()").value(1))
            .andExpect(jsonPath("$.items[0].messageId").value("msg_${second.id}")).andExpect(jsonPath("$.items[0].readAt").isNotEmpty)
        mvc.perform(
            get("/api/v1/drivers/me/chat/messages").param("before", "msg_${other.id}").header(HttpHeaders.AUTHORIZATION, bearer(10)),
        ).andExpect(status().isBadRequest)
        mvc.perform(get("/api/v1/chat-rooms").header(HttpHeaders.AUTHORIZATION, bearer(10))).andExpect(status().isForbidden)
    }

    @Test
    fun `승인 대기와 반려 기사는 채팅 소켓 업그레이드가 금지되고 관리자는 연결할 수 있다`() {
        // given
        val uri = URI("ws://127.0.0.1:$port/api/v1/chat/ws")
        jdbc.update("UPDATE drivers SET account_status = 'PENDING_APPROVAL' WHERE driver_id = 20")

        // when & then
        HttpClient.newHttpClient().use { client ->
            val driverToken = tokens.createAccessToken(20, 20)
            fun assertUpgradeForbidden() {
                val rejected = assertThrows<CompletionException> {
                    client.newWebSocketBuilder().connectTimeout(Duration.ofSeconds(5))
                        .subprotocols("allcheck-chat.v1", "bearer.$driverToken").buildAsync(uri, Collector()).join()
                }
                assertThat((rejected.cause as WebSocketHandshakeException).response.statusCode()).isEqualTo(403)
            }
            assertUpgradeForbidden()
            jdbc.update(
                "UPDATE drivers SET account_status = 'REJECTED', rejected_at = now(), rejection_reason = '반려 테스트' WHERE driver_id = 20",
            )
            assertUpgradeForbidden()

            val adminToken = tokens.createAccessToken(1, 1)
            val socket = client.newWebSocketBuilder().connectTimeout(Duration.ofSeconds(5))
                .subprotocols("allcheck-chat.v1", "bearer.$adminToken").buildAsync(uri, Collector()).join()
            try {
                assertThat(socket.subprotocol).isEqualTo("allcheck-chat.v1")
            } finally {
                socket.abort()
            }
        }
    }

    @Test
    fun `채팅 구독은 본인과 유효 세션만 허용하고 연결이 없으면 등록 기기에 식별자 푸시를 보낸다`() {
        // given
        jdbc.update(
            """
            INSERT INTO identity_push_devices(installation_id, account_id, session_id, platform, push_token, token_hash, created_at, updated_at)
            VALUES ('driver-device', 10, 10, 'ANDROID', 'fixture-token', encode(sha256(convert_to('fixture-token', 'UTF8')), 'hex'), now(), now())
            """.trimIndent(),
        )
        val uri = URI("ws://127.0.0.1:$port/api/v1/chat/ws")
        HttpClient.newHttpClient().use { client ->
            val unauthorized = assertThrows<CompletionException> {
                client.newWebSocketBuilder().connectTimeout(Duration.ofSeconds(5)).buildAsync(uri, Collector()).join()
            }
            assertThat((unauthorized.cause as WebSocketHandshakeException).response.statusCode()).isEqualTo(401)
            val listener = Collector()
            val socket = client.newWebSocketBuilder().connectTimeout(Duration.ofSeconds(5))
                .subprotocols("allcheck-chat.v1", "bearer.${tokens.createAccessToken(10, 10)}").buildAsync(uri, listener).join()
            try {
                assertThat(socket.subprotocol).isEqualTo("allcheck-chat.v1")
                socket.sendText("""{"type":"SUBSCRIBE","driverId":20}""", true).join()
                assertThat(listener.next().path("code").asString()).isEqualTo("CHAT_NOT_ALLOWED")
                socket.sendText("""{"type":"SUBSCRIBE","driverId":10}""", true).join()
                assertThat(listener.next().path("type").asString()).isEqualTo("SUBSCRIBED")

                // when
                val admin = ChatActor(1, ChatSide.ADMIN)
                val message = chat.send(admin, 10, "online", "웹소켓 메시지")
                worker.dispatchEvents()

                // then
                val event = listener.next()
                assertThat(event.path("messageId").asString()).isEqualTo("msg_${message.id}")
                assertThat(event.has("body")).isFalse()
                assertThat(count("chat_push_deliveries")).isZero()

                socket.sendText("""{"type":"UNSUBSCRIBE"}""", true).join()
                assertThat(listener.next().path("type").asString()).isEqualTo("UNSUBSCRIBED")
                val offline = chat.send(admin, 10, "offline", "푸시에 포함되지 않는 본문")
                worker.dispatchEvents()
                worker.dispatchPush()
                worker.dispatchPush()
                val fcm = push.received.poll(5, TimeUnit.SECONDS)
                assertThat(fcm).isNotNull().contains("chat_received", "msg_${offline.id}", "fixture-token")
                    .doesNotContain(offline.body)
                assertThat(push.received).isEmpty()
                assertThat(jdbc.queryForObject("SELECT status FROM chat_push_deliveries", String::class.java)).isEqualTo("PROVIDER_ACCEPTED")
                jdbc.update("UPDATE identity_auth_sessions SET revoked_at = now() WHERE session_id = 10")
                socket.sendText("""{"type":"PING"}""", true).join()
                assertThat(listener.closed.poll(5, TimeUnit.SECONDS)).isEqualTo(1008)
            } finally {
                socket.abort()
            }
        }
    }

    private fun bearer(accountId: Long) = "Bearer ${tokens.createAccessToken(accountId, accountId)}"

    private fun count(table: String): Long = checkNotNull(jdbc.queryForObject("SELECT count(*) FROM $table", Long::class.java))

    private fun send(path: String, accountId: Long, key: String, body: String) = post(path)
        .header(HttpHeaders.AUTHORIZATION, bearer(accountId)).header("Idempotency-Key", key)
        .contentType(MediaType.APPLICATION_JSON).content("""{"body":"$body"}""")

    private class Collector : WebSocket.Listener {
        private val frames = LinkedBlockingQueue<String>()
        val closed = LinkedBlockingQueue<Int>()
        private val text = StringBuilder()

        override fun onText(webSocket: WebSocket, data: CharSequence, last: Boolean): java.util.concurrent.CompletionStage<*>? {
            text.append(data)
            if (last) {
                frames.add(text.toString())
                text.clear()
            }
            webSocket.request(1)
            return null
        }

        override fun onClose(webSocket: WebSocket, statusCode: Int, reason: String): java.util.concurrent.CompletionStage<*>? {
            closed.add(statusCode)
            return null
        }

        fun next() = JsonMapper.builder().build().readTree(checkNotNull(frames.poll(5, TimeUnit.SECONDS)) { "No WebSocket frame received" })
    }

    @TestConfiguration
    class Fixtures {
        @Bean
        fun pushFixture() = PushFixture()

        @Bean
        fun chatTestFcm(fixture: PushFixture) = FcmConnection(
            "chat-test",
            RestClient.builder().baseUrl("http://127.0.0.1:${fixture.server.address.port}").build(),
            { "loopback-access-token" },
        )
    }

    class PushFixture : AutoCloseable {
        val received = LinkedBlockingQueue<String>()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/v1/projects/chat-test/messages:send") { exchange ->
                received.add(exchange.requestBody.bufferedReader().use { it.readText() })
                val response = """{"name":"projects/chat-test/messages/test"}""".toByteArray()
                exchange.sendResponseHeaders(200, response.size.toLong())
                exchange.responseBody.use { it.write(response) }
            }
            start()
        }

        override fun close() = server.stop(0)
    }

    companion object {
        private val schema = PostgresTestSchema("chat_test")

        @JvmStatic
        @DynamicPropertySource
        fun postgres(registry: DynamicPropertyRegistry) = schema.register(registry)
    }
}
