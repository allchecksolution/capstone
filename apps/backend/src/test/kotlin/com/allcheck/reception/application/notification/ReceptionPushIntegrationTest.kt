package com.allcheck.reception.application.notification

import com.allcheck.global.notification.FcmConnection
import com.allcheck.reception.ReceptionPushBinding
import com.allcheck.reception.ReceptionPushDevices
import com.allcheck.reception.application.analysis.ReceptionAnalysisTransactions
import com.allcheck.reception.application.recording.ReceptionRecordingTransactions
import com.allcheck.reception.application.recording.RecordingUploadMetadata
import com.allcheck.reception.application.review.ReceptionQueryService
import com.allcheck.reception.application.review.ReceptionService
import com.allcheck.reception.domain.vo.ReceptionExternalId
import com.allcheck.reception.domain.vo.analysis.ReceptionAiOriginal
import com.allcheck.reception.domain.vo.analysis.ReceptionStructuredResult
import com.allcheck.reception.domain.vo.review.ReceptionCreationData
import com.allcheck.reception.domain.vo.review.WorkType
import com.allcheck.reception.infrastructure.repository.notification.ReceptionAnalysisEventRepository
import com.allcheck.reception.infrastructure.repository.notification.ReceptionPushDeliveryRepository
import com.allcheck.support.PostgresTestSchema
import com.allcheck.support.S3TestFixtures
import com.sun.net.httpserver.HttpServer
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.context.annotation.Primary
import org.springframework.dao.DataAccessException
import org.springframework.http.client.JdkClientHttpRequestFactory
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.annotation.DirtiesContext
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.context.TestPropertySource
import org.springframework.web.client.RestClient
import tools.jackson.databind.json.JsonMapper
import java.net.InetSocketAddress
import java.net.http.HttpClient
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import javax.sql.DataSource

@SpringBootTest(properties = ["spring.jpa.show-sql=false"])
@ActiveProfiles("test")
@TestPropertySource("classpath:jwt-test.properties")
@Import(ReceptionPushIntegrationTest.FixtureConfig::class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class ReceptionPushIntegrationTest @Autowired constructor(
    private val sender: ReceptionPushService,
    private val push: ReceptionPushTransactions,
    private val receptions: ReceptionService,
    private val recordings: ReceptionRecordingTransactions,
    private val analyses: ReceptionAnalysisTransactions,
    private val queries: ReceptionQueryService,
    private val events: ReceptionAnalysisEventRepository,
    private val deliveries: ReceptionPushDeliveryRepository,
    private val dataSource: DataSource,
) {
    @BeforeEach
    fun resetExternalState() {
        clock.now.set(NOW)
        clock.barrier = null
        devices.bindings.clear()
        devices.invalidated.clear()
        oauthHook = null
        replies.clear()
        requests.clear()
    }

    @Test
    fun `현재 업로더의 기기에만 발송하고 재확장과 재호출은 중복 전송하지 않는다`() {
        // given
        val source = event(success = true)
        devices.bindings["device-a"] = Binding(1, "test-token-a")
        devices.bindings["device-b"] = Binding(1, "test-token-b")
        devices.bindings["other-owner"] = Binding(2, "test-other-token")

        // when
        sender.expand(PendingPushEvent(source.eventId, 1))
        sender.expand(PendingPushEvent(source.eventId, 1))
        val targets = deliveries.findAllByEventId(source.eventId)
        targets.forEach { sender.send(checkNotNull(it.deliveryId), TTL) }
        targets.forEach { sender.send(checkNotNull(it.deliveryId), TTL) }

        // then
        assertThat(targets.map { it.installationId }).containsExactlyInAnyOrder("device-a", "device-b")
        assertThat(requests).hasSize(2)
        deliveries.findAllByEventId(source.eventId).forEach {
            assertThat(it.status).isEqualTo("PROVIDER_ACCEPTED")
            assertThat(it.attemptCount).isEqualTo(1)
        }
        requests.forEach {
            val message = mapper.readTree(it).path("message")
            assertThat(message.path("token").asString()).isIn("test-token-a", "test-token-b")
            assertThat(message.path("data").size()).isEqualTo(4)
            assertThat(message.path("data").path("eventId").asString()).isEqualTo("evt_${source.eventId}")
            assertThat(message.path("data").path("eventType").asString()).isEqualTo("RECEPTION_ANALYSIS_SUCCEEDED")
        }
        assertThat(events.findById(source.eventId).orElseThrow().status).isEqualTo("EXPANDED")
    }

    @Test
    fun `일시 장애는 DB에 재시도 시각을 기록하고 Retry-After보다 일찍 보내지 않는다`() {
        // given
        val source = event()
        val id = target(source)
        replies.add(Reply(503, retryAfter = "180"))
        replies.add(Reply(503))

        // when & then
        sender.send(id, TTL)
        assertThat(deliveries.findById(id).orElseThrow().nextAttemptAt).isEqualTo(NOW.plusSeconds(180))
        clock.now.set(NOW.plusSeconds(179))
        sender.send(id, TTL)
        assertThat(requests).hasSize(1)
        clock.now.set(NOW.plusSeconds(180))
        sender.send(id, TTL)
        assertThat(deliveries.findById(id).orElseThrow().nextAttemptAt).isEqualTo(NOW.plusSeconds(300))
        clock.now.set(NOW.plusSeconds(300))
        sender.send(id, TTL)
        val stored = deliveries.findById(id).orElseThrow()
        assertThat(stored.status).isEqualTo("PROVIDER_ACCEPTED")
        assertThat(stored.attemptCount).isEqualTo(3)
        assertThat(requests).hasSize(3)
        assertThat(queries.analysis(1, source.receptionId, source.analysisId).error?.code).isEqualTo("STT_NO_CONTENT")
    }

    @Test
    fun `계속 실패하면 총 세 번에서 종료하며 AI 결과나 시도를 변경하지 않는다`() {
        // given
        val source = event(success = true)
        val id = target(source)
        repeat(3) { replies.add(Reply(500)) }

        // when
        repeat(3) {
            clock.now.set(deliveries.findById(id).orElseThrow().nextAttemptAt)
            sender.send(id, TTL)
        }
        clock.now.set(NOW.plusSeconds(3600))
        sender.send(id, TTL)

        // then
        val stored = deliveries.findById(id).orElseThrow()
        assertThat(stored.status).isEqualTo("FAILED")
        assertThat(stored.attemptCount).isEqualTo(3)
        assertThat(stored.lastError).isEqualTo("TEMPORARY_FAILURE")
        assertThat(requests).hasSize(3)
        assertThat(queries.analysis(1, source.receptionId, source.analysisId).status.name).isEqualTo("SUCCEEDED")
        assertThat(queries.detail(1, source.receptionId).aiOriginal?.notes).isEqualTo("보존할 원본")
    }

    @Test
    fun `등록 해제 응답은 재시도하지 않고 해당 토큰만 무효화한다`() {
        // given
        val id = target(event())
        replies.add(Reply(404, unregistered = true))

        // when
        sender.send(id, TTL)

        // then
        assertThat(deliveries.findById(id).orElseThrow().status).isEqualTo("FAILED")
        assertThat(deliveries.findById(id).orElseThrow().lastError).isEqualTo("UNREGISTERED")
        assertThat(devices.invalidated).containsExactly("device-a")
        assertThat(devices.bindings).doesNotContainKey("device-a")
        assertThat(requests).hasSize(1)
    }

    @Test
    fun `재시도 전에 다른 계정으로 이전한 기기는 발송하지 않는다`() {
        // given
        val id = target(event())
        replies.add(Reply(503))
        sender.send(id, TTL)
        devices.bindings["device-a"] = Binding(2, "test-token-rebound")
        clock.now.set(NOW.plusSeconds(60))

        // when
        sender.send(id, TTL)

        // then
        assertThat(deliveries.findById(id).orElseThrow().status).isEqualTo("SKIPPED")
        assertThat(requests).hasSize(1)
    }

    @Test
    fun `OAuth 준비 중 로그아웃하면 마지막 소유권 검사로 발송을 생략한다`() {
        // given
        val id = target(event())
        oauthHook = { devices.bindings.remove("device-a") }

        // when
        sender.send(id, TTL)

        // then
        assertThat(deliveries.findById(id).orElseThrow().status).isEqualTo("SKIPPED")
        assertThat(requests).isEmpty()
    }

    @Test
    fun `OAuth 준비가 실행권 기한을 넘기면 원격 전송 없이 재예약한다`() {
        // given
        val id = target(event())
        oauthHook = { clock.now.set(NOW.plus(TTL)) }

        // when
        sender.send(id, TTL)

        // then
        assertThat(deliveries.findById(id).orElseThrow().status).isEqualTo("PENDING")
        assertThat(deliveries.findById(id).orElseThrow().lastFailureCode).isEqualTo("REMOTE_OUTCOME_UNKNOWN")
        assertThat(requests).isEmpty()
    }

    @Test
    fun `인증 준비 중 토큰이 갱신되면 이전 토큰을 보내지 않고 다음 시도에 최신 토큰을 사용한다`() {
        // given
        val id = target(event())
        oauthHook = { devices.bindings["device-a"] = Binding(1, "test-rotated-token") }

        // when
        sender.send(id, TTL)
        assertThat(requests).isEmpty()
        oauthHook = null
        clock.now.set(NOW.plusSeconds(60))
        sender.send(id, TTL)

        // then
        assertThat(deliveries.findById(id).orElseThrow().status).isEqualTo("PROVIDER_ACCEPTED")
        assertThat(mapper.readTree(requests.single()).path("message").path("token").asString()).isEqualTo("test-rotated-token")
    }

    @Test
    fun `만료된 발송 실행권은 결과 불명으로 재예약하고 이전 작업자의 응답은 무시한다`() {
        // given
        val id = target(event())
        val old = checkNotNull(push.claim(id, "old-worker", TTL))

        // when
        clock.now.set(NOW.plus(TTL))
        push.expire(id)
        push.accepted(old, "projects/test-project/messages/old")

        // then
        assertThat(deliveries.findById(id).orElseThrow().status).isEqualTo("PENDING")
        clock.now.set(deliveries.findById(id).orElseThrow().nextAttemptAt)
        sender.send(id, TTL)
        push.accepted(old, "projects/test-project/messages/old")
        assertThat(deliveries.findById(id).orElseThrow().status).isEqualTo("PROVIDER_ACCEPTED")
        assertThat(deliveries.findById(id).orElseThrow().providerMessageId).doesNotEndWith("/old")
        assertThat(requests).hasSize(1)
    }

    @Test
    fun `동시 이벤트 확장과 발송 요청은 하나의 기기 행과 한 번의 원격 호출만 커밋한다`() {
        // given
        val source = event()
        devices.bindings["device-a"] = Binding(1, "test-token-a")

        // when
        val expanded = concurrently { sender.expand(PendingPushEvent(source.eventId, 1)) }
        assertThat(expanded.count { it }).isEqualTo(1)
        val target = deliveries.findAllByEventId(source.eventId).single()
        concurrently { sender.send(checkNotNull(target.deliveryId), TTL) }

        // then
        assertThat(deliveries.findAllByEventId(source.eventId)).hasSize(1)
        assertThat(deliveries.findById(checkNotNull(target.deliveryId)).orElseThrow().status).isEqualTo("PROVIDER_ACCEPTED")
        assertThat(deliveries.findById(checkNotNull(target.deliveryId)).orElseThrow().attemptCount).isEqualTo(1)
        assertThat(requests).hasSize(1)
    }

    @Test
    fun `현재 접수 접근 문맥을 잃은 이벤트와 수신 기기 없는 이벤트는 발송 없이 종료한다`() {
        // given
        val stale = event()
        devices.bindings["device-a"] = Binding(1, "test-token-a")
        JdbcTemplate(dataSource).update("UPDATE receptions SET latest_analysis_request_id=NULL WHERE reception_id=?", stale.receptionId)

        // when
        sender.expand(PendingPushEvent(stale.eventId, 1))
        devices.bindings.clear()
        val noDevices = event()
        sender.expand(PendingPushEvent(noDevices.eventId, 1))

        // then
        assertThat(events.findById(stale.eventId).orElseThrow().status).isEqualTo("SKIPPED")
        assertThat(events.findById(noDevices.eventId).orElseThrow().status).isEqualTo("SKIPPED")
        assertThat(deliveries.findAllByEventId(stale.eventId)).isEmpty()
        assertThat(requests).isEmpty()
    }

    private fun target(source: Source): Long {
        devices.bindings["device-a"] = Binding(1, "test-token-a")
        sender.expand(PendingPushEvent(source.eventId, 1))
        return checkNotNull(deliveries.findAllByEventId(source.eventId).single().deliveryId)
    }

    private fun event(success: Boolean = false): Source {
        val reception = receptions.create(1, ReceptionCreationData())
        val id = ReceptionExternalId.RECEPTION.parse(reception.receptionId)
        val metadata = RecordingUploadMetadata("call.wav", "audio/wav", 1024, S3TestFixtures.DIGEST)
        val reserved = recordings.reserve(1, id, 1, metadata, "test-bucket")
        val accepted = recordings.complete(1, id, reserved.recordingId, 2, metadata)
        analyses.claim(accepted.analysisId, "source-worker", Duration.ofMinutes(1))
        analyses.transcribed(accepted.analysisId, "source-worker", if (success) "테스트 원문" else " ", "source-trace")
        if (success) {
            analyses.storeTranscript(accepted.analysisId, "source-worker", "테스트 원문", "source-trace")
            analyses.startStructuring(accepted.analysisId, "source-worker", "source-trace")
            analyses.structured(accepted.analysisId, "source-worker", ReceptionStructuredResult(ReceptionAiOriginal(workType = WorkType.BOILER, notes = "보존할 원본"), emptyList()), "source-trace")
            analyses.apply(accepted.analysisId, "source-worker", "source-trace")
        }
        return Source(checkNotNull(events.findByAnalysisRequestId(accepted.analysisId)?.eventId), id, accepted.analysisId)
    }

    private fun concurrently(action: () -> Unit): List<Boolean> {
        val pool = Executors.newFixedThreadPool(2)
        clock.barrier = CountDownLatch(2)
        return try {
            List(2) {
                pool.submit<Boolean> {
                    try {
                        action()
                        true
                    } catch (_: DataAccessException) {
                        false
                    }
                }
            }.map { it.get(15, TimeUnit.SECONDS) }
        } finally {
            clock.barrier = null
            pool.shutdownNow()
        }
    }

    @AfterAll
    fun cleanup() {
        server.stop(0)
        database.remove(dataSource)
    }

    data class Source(val eventId: Long, val receptionId: Long, val analysisId: Long)
    data class Binding(val adminId: Long, val token: String)
    data class Reply(val status: Int, val retryAfter: String? = null, val unregistered: Boolean = false)

    class Devices : ReceptionPushDevices {
        val bindings = ConcurrentHashMap<String, Binding>()
        val invalidated = CopyOnWriteArrayList<String>()
        override fun activeInstallations(adminId: Long) = bindings.filterValues { it.adminId == adminId }.keys.toList()
        override fun currentBinding(adminId: Long, installationId: String) = bindings[installationId]?.takeIf { it.adminId == adminId }?.let { ReceptionPushBinding(it.token, 0) }
        override fun invalidateBinding(adminId: Long, installationId: String, rejected: ReceptionPushBinding) {
            bindings.computeIfPresent(installationId) { _, value ->
                if (value.adminId == adminId && value.token == rejected.token) {
                    invalidated.add(installationId)
                    null
                } else {
                    value
                }
            }
        }
    }

    class TestClock : Clock() {
        val now = AtomicReference(NOW)

        @Volatile var barrier: CountDownLatch? = null
        override fun instant(): Instant {
            barrier?.let {
                it.countDown()
                check(it.await(10, TimeUnit.SECONDS))
            }
            return now.get()
        }
        override fun getZone(): ZoneId = ZoneOffset.UTC
        override fun withZone(zone: ZoneId): Clock = fixed(now.get(), zone)
    }

    @TestConfiguration(proxyBeanMethods = false)
    class FixtureConfig {
        @Bean @Primary
        fun pushClock(): Clock = clock

        @Bean @Primary
        fun pushDevices(): ReceptionPushDevices = devices

        @Bean(destroyMethod = "close")
        fun fakeFcmConnection(): FcmConnection {
            val http = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build()
            val factory = JdkClientHttpRequestFactory(http)
            factory.setReadTimeout(Duration.ofSeconds(2))
            val client = RestClient.builder().baseUrl("http://127.0.0.1:${server.address.port}").requestFactory(factory).build()
            return FcmConnection("test-project", client, {
                oauthHook?.invoke()
                "test-oauth"
            }, http)
        }
    }

    companion object {
        private val NOW = Instant.parse("2026-11-17T06:00:00Z")
        private val TTL = Duration.ofSeconds(30)
        private val clock = TestClock()
        private val devices = Devices()
        private val replies = ConcurrentLinkedQueue<Reply>()
        private val requests = CopyOnWriteArrayList<String>()
        private val mapper = JsonMapper.builder().build()
        private val database = PostgresTestSchema("reception_push")

        @Volatile private var oauthHook: (() -> Unit)? = null
        private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/") { exchange ->
                requests.add(exchange.requestBody.readAllBytes().toString(Charsets.UTF_8))
                val reply = replies.poll() ?: Reply(200)
                val details = if (reply.unregistered) listOf(mapOf("@type" to "type.googleapis.com/google.firebase.fcm.v1.FcmError", "errorCode" to "UNREGISTERED")) else emptyList()
                val response: Map<String, Any> = if (reply.status == 200) {
                    mapOf("name" to "projects/test-project/messages/receipt-${requests.size}")
                } else {
                    mapOf("error" to mapOf<String, Any>("message" to "unsafe-provider-body", "details" to details))
                }
                val bytes = mapper.writeValueAsBytes(response)
                exchange.responseHeaders.set("Content-Type", "application/json")
                reply.retryAfter?.let { exchange.responseHeaders.set("Retry-After", it) }
                exchange.sendResponseHeaders(reply.status, bytes.size.toLong())
                exchange.responseBody.use { it.write(bytes) }
            }
            start()
        }

        @JvmStatic @DynamicPropertySource
        fun properties(registry: DynamicPropertyRegistry) {
            database.register(registry)
        }
    }
}
