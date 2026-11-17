package com.allcheck.reception.infrastructure.ai

import com.allcheck.reception.domain.vo.WorkType
import com.sun.net.httpserver.HttpServer
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.springframework.beans.factory.support.StaticListableBeanFactory
import java.net.InetSocketAddress
import java.time.Duration
import java.time.OffsetDateTime
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors

class AiClientTest {
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    private val executor = Executors.newCachedThreadPool()
    private val requests = CopyOnWriteArrayList<String>()
    private var connection: AiConnection? = null

    @AfterEach
    fun close() {
        connection?.close()
        server.stop(0)
        executor.shutdownNow()
    }

    @Test
    fun `실제 HTTP 요청에서 내부 인증과 식별자를 전송하고 원문 및 시각 오프셋을 보존한다`() {
        // given
        val client = start { path ->
            if (path == "/internal/v1/transcribe") TRANSCRIBED else ANALYZED
        }

        // when
        val original = client.transcribe(transcribe())
        val result = client.analyze(analyze(original))

        // then
        assertThat(original).isEqualTo("  고객 원문\r\n둘째 줄 😀  ")
        assertThat(result.aiOriginal.workType).isEqualTo(WorkType.BOILER)
        assertThat(result.aiOriginal.notes).isEqualTo("방문 전에 연락")
        assertThat(result.aiOriginal.desiredVisitFrom.toString()).isEqualTo("2026-11-18T14:00+09:00")
        assertThat(result.missingFields).containsExactly("address") // AI 목록을 임의 보완하지 않는다.
        assertThat(requests).hasSize(2)
        assertThat(requests[0]).contains("test-internal-key", "\"recordingFileId\":\"file_rec_3\"", "downloadUrl")
        assertThat(requests[1]).contains("\"transcriptFileId\":\"file_stt_4\"", "2026-11-17T14:00:00+09:00", "Asia/Seoul", "\\r\\n")
    }

    @ParameterizedTest
    @ValueSource(strings = ["wrong-id", "job-id", "null-field", "unknown-field", "unknown-work-type", "duplicate-missing", "unknown-missing", "string-version", "fraction-version", "duplicate-property"])
    fun `불일치 식별자와 합의되지 않은 응답 구조를 성공으로 취급하지 않는다`(kind: String) {
        // given
        val json = when (kind) {
            "wrong-id" -> ANALYZED.replace("anl_2", "anl_99")
            "job-id" -> ANALYZED.replace("analysisRequestId", "jobId")
            "null-field" -> ANALYZED.replace("\"BOILER\"", "null")
            "unknown-field" -> ANALYZED.replace("\"workType\":", "\"contactPhone\":\"01012345678\",\"workType\":")
            "unknown-work-type" -> ANALYZED.replace("BOILER", "OTHER")
            "duplicate-missing" -> ANALYZED.replace("[\"address\"]", "[\"address\",\"address\"]")
            "string-version" -> ANALYZED.replace("\"receptionVersion\":3", "\"receptionVersion\":\"3\"")
            "fraction-version" -> ANALYZED.replace("\"receptionVersion\":3", "\"receptionVersion\":3.1")
            "duplicate-property" -> ANALYZED.replace("\"receptionVersion\":3", "\"receptionVersion\":3,\"receptionVersion\":3")
            else -> ANALYZED.replace("[\"address\"]", "[\"contactPhone\"]")
        }
        val client = start { json }

        // when & then
        assertThat(assertThrows<AiCallException> { client.analyze(analyze("원문")) }.kind).isEqualTo(AiFailureKind.INVALID_RESPONSE)
        assertThat(requests).hasSize(1)
    }

    @Test
    fun `AI 오류 본문을 전파하거나 재시도하지 않고 504를 타임아웃으로 구분한다`() {
        // given
        val client = start(status = 504) { "unsafe-provider-body-and-key" }

        // when
        val failure = assertThrows<AiCallException> { client.transcribe(transcribe()) }

        // then
        assertThat(failure.kind).isEqualTo(AiFailureKind.TIMEOUT)
        assertThat(failure.message).doesNotContain("unsafe-provider")
        assertThat(failure.cause).isNull()
        assertThat(requests).hasSize(1)
    }

    @ParameterizedTest
    @ValueSource(ints = [202, 302])
    fun `리디렉션을 따라가지 않으며 비동기 202도 동기 성공으로 해석하지 않는다`(status: Int) {
        // given
        val client = start(status = status) { TRANSCRIBED }

        // when & then
        assertThat(assertThrows<AiCallException> { client.transcribe(transcribe()) }.kind).isEqualTo(AiFailureKind.INVALID_RESPONSE)
        assertThat(requests).hasSize(1)
    }

    @Test
    fun `HTTP 응답 기한을 넘기면 한 번의 호출만 실패한다`() {
        // given
        val client = start(timeout = Duration.ofMillis(50), delayMillis = 300) { TRANSCRIBED }

        // when & then
        assertThat(assertThrows<AiCallException> { client.transcribe(transcribe()) }.kind).isEqualTo(AiFailureKind.TIMEOUT)
        assertThat(requests.size).isLessThanOrEqualTo(1)
    }

    @Test
    fun `실제 계약을 명시하지 않으면 AI 연동 활성화를 거부한다`() {
        // when & then
        assertThrows<IllegalArgumentException> { AiConnectionConfig().receptionAiConnection("http://127.0.0.1", "test-key", "job-id", SECOND, SECOND, SECOND) }
        assertThrows<IllegalArgumentException> { AiConnectionConfig().receptionAiConnection("http://ai.example.invalid", "test-key", "analysis-request-id-v1", SECOND, SECOND, SECOND) }
        val factory = StaticListableBeanFactory()
        val client = AiClient(factory.getBeanProvider(AiConnection::class.java))
        assertThat(assertThrows<AiCallException> { client.transcribe(transcribe()) }.kind).isEqualTo(AiFailureKind.UNAVAILABLE)
    }

    private fun start(status: Int = 200, timeout: Duration = Duration.ofSeconds(2), delayMillis: Long = 0, response: (String) -> String): AiClient {
        server.executor = executor
        server.createContext("/") { exchange ->
            val body = exchange.requestBody.readAllBytes().toString(Charsets.UTF_8)
            requests.add("${exchange.requestURI.path} ${exchange.requestHeaders.getFirst("X-Internal-Api-Key")} $body")
            if (delayMillis > 0) Thread.sleep(delayMillis)
            val bytes = response(exchange.requestURI.path).toByteArray(Charsets.UTF_8)
            exchange.responseHeaders.set("Content-Type", "application/json")
            exchange.responseHeaders.set("Location", "http://127.0.0.1:${server.address.port}/redirected")
            exchange.sendResponseHeaders(status, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
        val ready = AiConnectionConfig().receptionAiConnection("http://127.0.0.1:${server.address.port}", "test-internal-key", "analysis-request-id-v1", SECOND, timeout, timeout)
        connection = ready
        val factory = StaticListableBeanFactory()
        factory.addBean("connection", ready)
        return AiClient(factory.getBeanProvider(AiConnection::class.java))
    }

    private fun transcribe() = AiTranscribeRequest("anl_2", "rec_1", 3, "file_rec_3", AiRecordingSource("https://example.invalid/recording", OffsetDateTime.parse("2026-11-17T14:10:00+09:00"), "audio/mp4", 1024, AiFileChecksum("AQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQE=")))

    private fun analyze(original: String) = AiAnalyzeRequest("anl_2", "rec_1", 3, "file_rec_3", "file_stt_4", original, OffsetDateTime.parse("2026-11-17T14:00:00+09:00"))

    private companion object {
        val SECOND: Duration = Duration.ofSeconds(1)
        val TRANSCRIBED = """{"analysisRequestId":"anl_2","receptionId":"rec_1","receptionVersion":3,"recordingFileId":"file_rec_3","status":"SUCCEEDED","transcript":"  고객 원문\r\n둘째 줄 😀  "}"""
        val ANALYZED = """{"analysisRequestId":"anl_2","receptionId":"rec_1","receptionVersion":3,"recordingFileId":"file_rec_3","transcriptFileId":"file_stt_4","status":"SUCCEEDED","aiOriginal":{"workType":"BOILER","desiredVisitFrom":"2026-11-18T14:00:00+09:00","notes":"방문 전에 연락"},"missingFields":["address"]}"""
    }
}
