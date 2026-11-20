package com.allcheck.driver.application

import com.allcheck.driver.application.notification.DriverPushData
import com.allcheck.driver.application.notification.DriverPushSender
import com.allcheck.global.notification.FcmClient
import com.allcheck.global.notification.FcmConnection
import com.allcheck.identity.PushTarget
import com.sun.net.httpserver.HttpServer
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.springframework.beans.factory.support.StaticListableBeanFactory
import org.springframework.http.client.JdkClientHttpRequestFactory
import org.springframework.web.client.RestClient
import tools.jackson.databind.json.JsonMapper
import java.net.InetSocketAddress
import java.time.Clock
import java.util.concurrent.CopyOnWriteArrayList

class DriverPushSenderTest {
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    private val requests = CopyOnWriteArrayList<String>()
    private val target = PushTarget("private-account-id", "installation", "test-device-token", "digest")
    private val data = DriverPushData("event", "DRIVER_APPROVED", "driver", 1)

    @AfterEach
    fun close() {
        server.stop(0)
    }

    @Test
    fun `공통 HTTP 전송으로 기사 식별자와 기존 안내 알림을 보내고 접수 ID를 반환한다`() {
        // given
        val sender = sender(200, """{"name":"projects/test-project/messages/accepted"}""")

        // when
        val result = sender.send(target, data)

        // then
        assertThat(requests).hasSize(1)
        val message = JsonMapper.builder().build().readTree(requests.single()).path("message")
        assertThat(message.path("token").asString()).isEqualTo(target.token)
        assertThat(message.path("data").size()).isEqualTo(4)
        assertThat(message.path("data").path("eventId").asString()).isEqualTo(data.eventId)
        assertThat(message.path("data").path("eventType").asString()).isEqualTo(data.eventType)
        assertThat(message.path("data").path("driverId").asString()).isEqualTo(data.driverId)
        assertThat(message.path("data").path("revision").asString()).isEqualTo("1")
        assertThat(message.path("notification").path("title").asString()).isEqualTo("기사 가입 상태 안내")
        assertThat(message.path("notification").path("body").asString()).isEqualTo("앱에서 최신 가입 신청 상태를 확인해 주세요.")
        assertThat(requests.single()).doesNotContain(target.accountId, "rejectionReason", "accountNumber")
        assertThat(result?.messageId).isEqualTo("projects/test-project/messages/accepted")
        assertThat(result?.errorCode).isNull()
    }

    @ParameterizedTest
    @CsvSource("404, UNREGISTERED, UNREGISTERED, true", "503, UNAVAILABLE, TEMPORARY_FAILURE, false", "400, INVALID_ARGUMENT, REJECTED, false")
    fun `등록 해제된 토큰만 폐기 대상으로 분류하고 실패를 성공으로 기록하지 않는다`(status: Int, providerCode: String, expectedCode: String, invalidToken: Boolean) {
        // given
        val sender = sender(status, """{"error":{"details":[{"@type":"type.googleapis.com/google.firebase.fcm.v1.FcmError","errorCode":"$providerCode"}]}}""")

        // when
        val result = sender.send(target, data)

        // then
        assertThat(result?.messageId).isNull()
        assertThat(result?.errorCode).isEqualTo(expectedCode)
        assertThat(result?.invalidToken).isEqualTo(invalidToken)
        assertThat(requests).hasSize(1)
    }

    @Test
    fun `발송 직전 실행권을 잃으면 HTTP 요청 없이 취소 결과를 반환한다`() {
        // given
        val sender = sender(200, """{"name":"projects/test-project/messages/accepted"}""")

        // when
        val result = sender.send(target, data) { false }

        // then
        assertThat(result).isNull()
        assertThat(requests).isEmpty()
    }

    @Test
    fun `공통 FCM이 비활성이면 전송 성공으로 처리하지 않는다`() {
        // given
        val sender = DriverPushSender(FcmClient(StaticListableBeanFactory().getBeanProvider(FcmConnection::class.java), Clock.systemUTC()))

        // when
        val result = sender.send(target, data)

        // then
        assertThat(sender.isConfigured()).isFalse()
        assertThat(result?.messageId).isNull()
        assertThat(result?.errorCode).isEqualTo("UNAVAILABLE")
    }

    private fun sender(status: Int, body: String): DriverPushSender {
        server.createContext("/") { exchange ->
            requests.add(exchange.requestBody.readAllBytes().toString(Charsets.UTF_8))
            exchange.responseHeaders.set("Content-Type", "application/json")
            val bytes = body.toByteArray(Charsets.UTF_8)
            exchange.sendResponseHeaders(status, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
        val rest = RestClient.builder().baseUrl("http://127.0.0.1:${server.address.port}").requestFactory(JdkClientHttpRequestFactory()).build()
        val beans = StaticListableBeanFactory()
        beans.addBean("connection", FcmConnection("test-project", rest, { "test-oauth" }))
        return DriverPushSender(FcmClient(beans.getBeanProvider(FcmConnection::class.java), Clock.systemUTC()))
    }
}
