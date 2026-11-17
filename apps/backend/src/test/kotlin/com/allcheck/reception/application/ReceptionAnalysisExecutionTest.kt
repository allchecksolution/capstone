package com.allcheck.reception.application

import com.allcheck.reception.domain.vo.ReceptionConfirmationData
import com.allcheck.reception.domain.vo.ReceptionCreationData
import com.allcheck.reception.domain.vo.ReceptionExternalId
import com.allcheck.reception.domain.vo.WorkType
import com.allcheck.reception.infrastructure.ai.AiConnection
import com.allcheck.reception.infrastructure.ai.AiConnectionConfig
import com.allcheck.reception.infrastructure.repository.ReceptionAnalysisEventRepository
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
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.annotation.DirtiesContext
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.context.TestPropertySource
import software.amazon.awssdk.services.s3.presigner.S3Presigner
import tools.jackson.databind.json.JsonMapper
import java.net.InetSocketAddress
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicReference
import javax.sql.DataSource

@SpringBootTest(properties = ["spring.jpa.show-sql=false"])
@ActiveProfiles("test")
@TestPropertySource("classpath:jwt-test.properties")
@Import(ReceptionAnalysisExecutionTest.ExecutionFixture::class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class ReceptionAnalysisExecutionTest @Autowired constructor(
    private val service: ReceptionAnalysisService,
    private val receptions: ReceptionService,
    private val recordings: ReceptionRecordingTransactions,
    private val transactions: ReceptionAnalysisTransactions,
    private val queries: ReceptionQueryService,
    private val events: ReceptionAnalysisEventRepository,
    private val dataSource: DataSource,
) {
    @BeforeEach
    fun resetExternalFixture() {
        clock.now.set(NOW)
        mode.set("success")
        requests.clear()
    }

    @Test
    fun `파일명 연락처는 AI 자동 채움을 막지 않고 관리자 수정과 확정 취소 후에도 수정값을 우선한다`() {
        // given
        val accepted = accept("rec_테스트업체_01012345678_01012345678@261117_103802.wav")

        // when
        service.execute(accepted.analysisId, BUDGET)
        val detail = queries.detail(1, accepted.receptionId)

        // then
        assertThat(detail.finalData.contactPhone).isEqualTo("01012345678")
        assertThat(detail.finalData.workType).isEqualTo(WorkType.WATERPROOFING)
        assertThat(detail.finalData.notes).isEqualTo("방문 전 연락")
        assertThat(detail.recordingReceivedAt?.toInstant()).isEqualTo(NOW) // 파일명 시각으로 기준 시각을 추정하지 않는다.
        val reviewed = ReceptionConfirmationData(WorkType.BOILER, "확인한 증상", "확인한 주소", NOW.plusSeconds(3600).atOffset(ZoneOffset.UTC), NOW.plusSeconds(7200).atOffset(ZoneOffset.UTC), "01099998888", "관리자 메모")
        receptions.confirm(1, accepted.receptionId, 4, reviewed)
        receptions.reopen(1, accepted.receptionId, 5)
        assertThat(queries.detail(1, accepted.receptionId).finalData.contactPhone).isEqualTo("01099998888")
        assertThat(queries.detail(1, accepted.receptionId).aiOriginal?.notes).isEqualTo("방문 전 연락")
    }

    @Test
    fun `파일명 연락처는 STT 실패 후에도 검토에 제공하고 서로 다른 번호는 비워 둔다`() {
        // given
        mode.set("blank")
        val known = accept("rec_테스트업체_01012345678_01012345678@261117_103802.wav")
        val ambiguous = accept("rec_테스트업체_01012345678_01098765432@261117_103802.wav")

        // when
        service.execute(known.analysisId, BUDGET)
        service.execute(ambiguous.analysisId, BUDGET)

        // then
        assertThat(queries.detail(1, known.receptionId).finalData.contactPhone).isEqualTo("01012345678")
        assertThat(queries.detail(1, known.receptionId).reviewEditable).isTrue()
        assertThat(queries.detail(1, ambiguous.receptionId).finalData.contactPhone).isNull()
    }

    @Test
    fun `실제 HTTP와 커밋 경계를 거쳐 원문 AI 원본 최신값 및 알림 의도를 보관한다`() {
        // given
        val accepted = accept()
        assertThat(transactions.pending(100)).contains(accepted.analysisId)

        // when
        service.execute(accepted.analysisId, BUDGET)
        service.execute(accepted.analysisId, BUDGET)

        // then
        val detail = queries.detail(1, accepted.receptionId)
        val attempt = queries.analysis(1, accepted.receptionId, accepted.analysisId)
        assertThat(attempt.status.name).isEqualTo("SUCCEEDED")
        assertThat(attempt.resultApplied).isTrue()
        assertThat(detail.reviewStatus.name).isEqualTo("DRAFT")
        assertThat(detail.reviewEditable).isTrue()
        assertThat(detail.version).isEqualTo(4)
        assertThat(detail.transcript).isEqualTo(ORIGINAL)
        assertThat(detail.aiOriginal?.notes).isEqualTo("방문 전 연락")
        assertThat(detail.finalData.notes).isEqualTo("방문 전 연락")
        assertThat(detail.finalData.contactPhone).isNull()
        assertThat(detail.referenceTime).isEqualTo(NOW.atOffset(ZoneOffset.ofHours(9)))
        assertThat(requests).hasSize(2)
        assertThat(requests[1]).contains("2026-11-17T15:00:00+09:00", "\\r\\n", "file_stt_")
        assertThat(events.findByAnalysisRequestId(accepted.analysisId)?.eventType).isEqualTo("RECEPTION_ANALYSIS_SUCCEEDED")
    }

    @Test
    fun `공백뿐인 전사문은 원문 저장과 구조화 호출 없이 실패하고 수동 검토를 허용한다`() {
        // given
        mode.set("blank")
        val accepted = accept()

        // when
        service.execute(accepted.analysisId, BUDGET)

        // then
        assertFailure(accepted, "STT_NO_CONTENT", transcriptStored = false)
        assertThat(requests).hasSize(1)
    }

    @Test
    fun `원문 INSERT 실패는 성공한 전사 체크포인트를 보존하고 별도 트랜잭션에서 실패를 기록한다`() {
        // given
        val accepted = accept()
        val jdbc = JdbcTemplate(dataSource)
        jdbc.execute("CREATE FUNCTION fail_execution_transcript() RETURNS trigger LANGUAGE plpgsql AS 'BEGIN IF NEW.reception_id = ${accepted.receptionId} THEN RAISE EXCEPTION ''test storage failure''; END IF; RETURN NEW; END'")
        jdbc.execute("CREATE TRIGGER fail_execution_transcript BEFORE INSERT ON reception_transcripts FOR EACH ROW EXECUTE FUNCTION fail_execution_transcript()")

        // when
        try {
            service.execute(accepted.analysisId, BUDGET)
        } finally {
            jdbc.execute("DROP TRIGGER fail_execution_transcript ON reception_transcripts")
            jdbc.execute("DROP FUNCTION fail_execution_transcript()")
        }

        // then
        assertFailure(accepted, "TRANSCRIPT_STORAGE_FAILED", transcriptStored = false)
        assertThat(queries.analysis(1, accepted.receptionId, accepted.analysisId).sttStatus.name).isEqualTo("SUCCEEDED")
        assertThat(requests).hasSize(1)
    }

    @Test
    fun `구조화 HTTP 오류가 나도 이미 보관된 STT 원문과 성공 단계를 유지한다`() {
        // given
        mode.set("structure-failure")
        val accepted = accept()

        // when
        service.execute(accepted.analysisId, BUDGET)

        // then
        assertFailure(accepted, "AI_STRUCTURING_FAILED", transcriptStored = true)
        assertThat(requests).hasSize(2)
    }

    @Test
    fun `잘못된 응답 식별자는 원문을 만들거나 구조화하지 않는다`() {
        // given
        mode.set("wrong-identity")
        val accepted = accept()

        // when
        service.execute(accepted.analysisId, BUDGET)

        // then
        assertFailure(accepted, "AI_STT_FAILED", transcriptStored = false)
        assertThat(requests).hasSize(1)
    }

    @Test
    fun `STT 응답이 전체 기한과 같으면 성공 HTTP 응답이어도 다음 단계로 진행하지 않는다`() {
        // given
        mode.set("late-stt")
        val accepted = accept()

        // when
        service.execute(accepted.analysisId, BUDGET)

        // then
        assertFailure(accepted, "ANALYSIS_TIMEOUT", transcriptStored = false)
        assertThat(requests).hasSize(1)
    }

    @Test
    fun `구조화 응답이 전체 기한에 도달하면 원문만 보존하고 AI 결과는 적용하지 않는다`() {
        // given
        mode.set("late-structure")
        val accepted = accept()

        // when
        service.execute(accepted.analysisId, BUDGET)

        // then
        assertFailure(accepted, "ANALYSIS_TIMEOUT", transcriptStored = true)
        assertThat(queries.detail(1, accepted.receptionId).aiOriginal).isNull()
        assertThat(requests).hasSize(2)
    }

    @Test
    fun `재시작 시 PROCESSING은 다시 실행하지 않고 기한 만료만 기록한다`() {
        // given
        val accepted = accept()
        transactions.claim(accepted.analysisId, "interrupted-worker", BUDGET)

        // when
        service.execute(accepted.analysisId, BUDGET)
        clock.now.set(NOW.plus(BUDGET))
        assertThat(transactions.overdue(100)).contains(accepted.analysisId)
        transactions.expire(accepted.analysisId, "monitor-test")
        service.execute(accepted.analysisId, BUDGET)

        // then
        assertFailure(accepted, "ANALYSIS_TIMEOUT", transcriptStored = false)
        assertThat(requests).isEmpty()
    }

    private fun assertFailure(accepted: RecordingAcceptance, code: String, transcriptStored: Boolean) {
        val attempt = queries.analysis(1, accepted.receptionId, accepted.analysisId)
        val detail = queries.detail(1, accepted.receptionId)
        assertThat(attempt.status.name).isEqualTo("FAILED")
        assertThat(attempt.error?.code).isEqualTo(code)
        assertThat(detail.reviewEditable).isTrue()
        assertThat(detail.version).isEqualTo(3)
        assertThat(detail.transcript).isEqualTo(if (transcriptStored) ORIGINAL else null)
        assertThat(detail.aiOriginal).isNull()
        assertThat(events.findByAnalysisRequestId(accepted.analysisId)?.eventType).isEqualTo("RECEPTION_ANALYSIS_FAILED")
    }

    private fun accept(filename: String = "call.wav"): RecordingAcceptance {
        val id = ReceptionExternalId.RECEPTION.parse(receptions.create(1, ReceptionCreationData()).receptionId)
        val metadata = RecordingUploadMetadata(filename, "audio/wav", 1024, S3TestFixtures.DIGEST)
        val reserved = recordings.reserve(1, id, 1, metadata, "test-bucket")
        return recordings.complete(1, id, reserved.recordingId, 2, metadata)
    }

    @AfterAll
    fun removeOwnedResources() {
        server.stop(0)
        serverExecutor.shutdownNow()
        database.remove(dataSource)
    }

    @TestConfiguration(proxyBeanMethods = false)
    class ExecutionFixture {
        @Bean
        @Primary
        fun executionClock(): Clock = clock

        @Bean(destroyMethod = "close")
        fun offlineSigner(): S3Presigner = S3TestFixtures.signer()

        @Bean(destroyMethod = "close")
        fun testAiConnection(): AiConnection = AiConnectionConfig().receptionAiConnection("http://127.0.0.1:${server.address.port}", "test-internal-key", "analysis-request-id-v1", Duration.ofSeconds(1), Duration.ofSeconds(2), Duration.ofSeconds(2))
    }

    class MutableClock(initial: Instant) : Clock() {
        val now = AtomicReference(initial)
        override fun instant(): Instant = now.get()
        override fun getZone(): ZoneId = ZoneOffset.UTC
        override fun withZone(zone: ZoneId): Clock = fixed(instant(), zone)
    }

    companion object {
        private const val ORIGINAL = "  고객 원문\r\n메모 😀  "
        private val NOW = Instant.parse("2026-11-17T06:00:00Z")
        private val BUDGET = Duration.ofSeconds(10)
        private val clock = MutableClock(NOW)
        private val database = PostgresTestSchema("analysis_execution")
        private val mode = AtomicReference("success")
        private val requests = CopyOnWriteArrayList<String>()
        private val mapper = JsonMapper.builder().build()
        private val serverExecutor = Executors.newCachedThreadPool()
        private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            executor = serverExecutor
            createContext("/") { exchange ->
                val text = exchange.requestBody.readAllBytes().toString(Charsets.UTF_8)
                requests.add("${exchange.requestURI.path} $text")
                val input = mapper.readTree(text)
                val structuring = exchange.requestURI.path.endsWith("/analyze")
                val selected = mode.get()
                val response = linkedMapOf<String, Any>(
                    "analysisRequestId" to input.get("analysisRequestId"),
                    "receptionId" to input.get("receptionId"),
                    "receptionVersion" to input.get("receptionVersion"),
                    "recordingFileId" to input.get("recordingFileId"),
                    "status" to "SUCCEEDED",
                )
                if (structuring) {
                    response["transcriptFileId"] = input.get("transcriptFileId")
                    response["aiOriginal"] = mapOf("workType" to "WATERPROOFING", "symptoms" to "옥상 누수", "address" to "서울시 예시로 1", "notes" to "방문 전 연락")
                    response["missingFields"] = emptyList<String>()
                } else {
                    response["transcript"] = if (selected == "blank") " \n\t " else ORIGINAL
                }
                if (selected == "wrong-identity") response["receptionId"] = "rec_9999999"
                if ((!structuring && selected == "late-stt") || (structuring && selected == "late-structure")) clock.now.set(NOW.plus(BUDGET))
                val bytes = mapper.writeValueAsBytes(response)
                exchange.responseHeaders.set("Content-Type", "application/json")
                exchange.sendResponseHeaders(if (structuring && selected == "structure-failure") 500 else 200, bytes.size.toLong())
                exchange.responseBody.use { it.write(bytes) }
            }
            start()
        }

        @JvmStatic
        @DynamicPropertySource
        fun properties(registry: DynamicPropertyRegistry) {
            database.register(registry)
        }
    }
}
