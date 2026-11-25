package com.allcheck.global.notification

import com.sun.net.httpserver.HttpServer
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.springframework.beans.factory.support.StaticListableBeanFactory
import org.springframework.http.client.JdkClientHttpRequestFactory
import org.springframework.web.client.RestClient
import tools.jackson.databind.json.JsonMapper
import java.net.InetSocketAddress
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.CopyOnWriteArrayList

class FcmClientTest {
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    private val requests = CopyOnWriteArrayList<String>()
    private val mapper = JsonMapper.builder().build()

    @AfterEach
    fun close() {
        server.stop(0)
    }

    @Test
    fun `OAuth 인증을 붙여 한 번 전송하고 공급자 접수 식별자만 반환한다`() {
        // given
        val client = client(200, """{"name":"projects/test-project/messages/receipt-1"}""")

        // when
        val receipt = client.send("test-device-token", mapOf("eventId" to "evt_1"))

        // then
        assertThat(receipt).isEqualTo("projects/test-project/messages/receipt-1")
        assertThat(requests).hasSize(1)
        assertThat(requests[0]).startsWith("/v1/projects/test-project/messages:send Bearer test-oauth ")
        val body = mapper.readTree(requests[0].substringAfter("Bearer test-oauth "))
        assertThat(body.path("message").path("token").asString()).isEqualTo("test-device-token")
        assertThat(body.path("message").path("data").path("eventId").asString()).isEqualTo("evt_1")
        assertThat(body.path("message").has("notification")).isFalse()
    }

    @ParameterizedTest
    @ValueSource(ints = [429, 500, 503])
    fun `일시 장애와 Retry-After만 분류하고 자체 재시도하거나 공급자 본문을 전파하지 않는다`(status: Int) {
        // given
        val client = client(status, """{"error":{"message":"unsafe-private-token","details":[]}}""", "120")

        // when
        val failure = assertThrows<FcmException> { client.send("test-device-token", mapOf("eventId" to "evt_1")) }

        // then
        assertThat(failure.failure).isEqualTo(FcmFailure.TEMPORARY_FAILURE)
        assertThat(failure.retryAfter).isEqualTo(Duration.ofMinutes(2))
        assertThat(failure.cause).isNull()
        assertThat(failure.message).doesNotContain("unsafe-private")
        assertThat(requests).hasSize(1)
    }

    @Test
    fun `등록 해제된 기기만 영구 실패로 구분하고 날짜 형식 Retry-After도 해석한다`() {
        // given
        val body = """{"error":{"details":[{"@type":"type.googleapis.com/google.firebase.fcm.v1.FcmError","errorCode":"UNREGISTERED"}]}}"""
        val client = client(404, body, "Tue, 17 Nov 2026 06:02:00 GMT")

        // when
        val failure = assertThrows<FcmException> { client.send("test-token", mapOf("eventId" to "evt_1")) }

        // then
        assertThat(failure.failure).isEqualTo(FcmFailure.UNREGISTERED)
        assertThat(failure.failure.retryable).isFalse()
        assertThat(failure.retryAfter).isEqualTo(Duration.ofMinutes(2))
    }

    @Test
    fun `인증 갱신 후 실행권을 잃으면 실제 HTTP 전송을 하지 않는다`() {
        // given
        val client = client(200, """{"name":"projects/test-project/messages/receipt-1"}""")

        // when & then
        assertThat(assertThrows<FcmException> { client.send("test-token", mapOf("eventId" to "evt_1")) { false } }.failure).isEqualTo(FcmFailure.CANCELLED)
        assertThat(requests).isEmpty()
    }

    @Test
    fun `잘못된 성공 본문을 수신 완료로 오인하지 않는다`() {
        // given
        val client = client(200, """{"name":"unrelated-provider-value"}""")

        // when & then
        assertThat(assertThrows<FcmException> { client.send("test-token", mapOf("eventId" to "evt_1")) }.failure).isEqualTo(FcmFailure.REMOTE_OUTCOME_UNKNOWN)
    }

    @Test
    fun `리디렉션을 따라 OAuth 헤더나 기기 토큰을 다른 요청으로 전달하지 않는다`() {
        // given
        val client = client(302, "{}")

        // when & then
        assertThat(assertThrows<FcmException> { client.send("test-token", mapOf("eventId" to "evt_1")) }.failure).isEqualTo(FcmFailure.INVALID_RESPONSE)
        assertThat(requests).hasSize(1)
    }

    private fun client(status: Int, body: String, retryAfter: String? = null): FcmClient {
        server.createContext("/") { exchange ->
            requests.add("${exchange.requestURI.path} ${exchange.requestHeaders.getFirst("Authorization")} ${exchange.requestBody.readAllBytes().toString(Charsets.UTF_8)}")
            exchange.responseHeaders.set("Content-Type", "application/json")
            exchange.responseHeaders.set("Location", "http://127.0.0.1:${server.address.port}/redirected")
            retryAfter?.let { exchange.responseHeaders.set("Retry-After", it) }
            val bytes = body.toByteArray(Charsets.UTF_8)
            exchange.sendResponseHeaders(status, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
        val factory = JdkClientHttpRequestFactory()
        factory.setReadTimeout(Duration.ofSeconds(2))
        val rest = RestClient.builder().baseUrl("http://127.0.0.1:${server.address.port}").requestFactory(factory).build()
        val beans = StaticListableBeanFactory()
        beans.addBean("connection", FcmConnection("test-project", rest, { "test-oauth" }))
        return FcmClient(beans.getBeanProvider(FcmConnection::class.java), Clock.fixed(Instant.parse("2026-11-17T06:00:00Z"), ZoneOffset.UTC))
    }
}
