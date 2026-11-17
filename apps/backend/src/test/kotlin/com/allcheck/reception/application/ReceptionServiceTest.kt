package com.allcheck.reception.application

import com.allcheck.global.config.HibernateConfig
import com.allcheck.global.exception.BusinessException
import com.allcheck.global.exception.ErrorCode
import com.allcheck.global.exception.GlobalErrorCode
import com.allcheck.reception.domain.entity.Reception
import com.allcheck.reception.domain.entity.ReceptionAnalysis
import com.allcheck.reception.domain.entity.ReceptionRecording
import com.allcheck.reception.domain.entity.ReceptionTranscript
import com.allcheck.reception.domain.vo.ReceptionAiOriginal
import com.allcheck.reception.domain.vo.ReceptionAnalysisStatus
import com.allcheck.reception.domain.vo.ReceptionConfirmationData
import com.allcheck.reception.domain.vo.ReceptionReviewStatus
import com.allcheck.reception.domain.vo.ReceptionStructuredResult
import com.allcheck.reception.domain.vo.WorkType
import com.allcheck.reception.exception.ReceptionErrorCode
import com.allcheck.reception.infrastructure.repository.ReceptionAnalysisRepository
import com.allcheck.reception.infrastructure.repository.ReceptionRecordingRepository
import com.allcheck.reception.infrastructure.repository.ReceptionRepository
import com.allcheck.reception.infrastructure.repository.ReceptionTranscriptRepository
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import org.junit.jupiter.params.provider.ValueSource
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.autoconfigure.ImportAutoConfiguration
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest
import org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.http.HttpStatus
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.annotation.DirtiesContext
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.util.ReflectionTestUtils
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.support.TransactionTemplate
import java.time.Clock
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.sql.DataSource

@DataJpaTest(properties = ["spring.jpa.hibernate.ddl-auto=validate", "spring.jpa.show-sql=false"])
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ImportAutoConfiguration(FlywayAutoConfiguration::class)
@Import(HibernateConfig::class, ReceptionService::class, ReceptionQueryService::class, ReceptionServiceTest.TestTimeConfig::class)
@ActiveProfiles("test")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class ReceptionServiceTest @Autowired constructor(
    private val service: ReceptionService,
    private val receptionRepository: ReceptionRepository,
    private val analysisRepository: ReceptionAnalysisRepository,
    private val recordingRepository: ReceptionRecordingRepository,
    private val transcriptRepository: ReceptionTranscriptRepository,
    private val transactionManager: PlatformTransactionManager,
    private val dataSource: DataSource,
    private val clock: ControlledClock,
) {
    @Test
    fun `확정과 취소 응답 시각은 DB 재조회와 같고 방문 종료 검사는 원래 서버 정밀도로 수행한다`() {
        // given
        val receptionId = manualReception()
        clock.currentTime = NOW.plusNanos(123_456_789)

        // when
        val confirmed = service.confirm(ADMIN, receptionId, 1, reviewedData())

        // then
        assertThat(confirmed.confirmedAt).isEqualTo(storedReception(receptionId).confirmedAt)
        clock.currentTime = NOW.plusNanos(987_654_321)
        val reopened = service.reopen(ADMIN, receptionId, 2)
        assertThat(reopened.updatedAt).isEqualTo(storedReception(receptionId).updatedAt)
        val equalToNow = reviewedData().copy(desiredVisitFrom = clock.currentTime.minusSeconds(3600).atOffset(ZoneOffset.UTC), desiredVisitTo = clock.currentTime.atOffset(ZoneOffset.UTC))
        assertRejected(receptionId, GlobalErrorCode.INVALID_INPUT_VALUE) { service.confirm(ADMIN, receptionId, 3, equalToNow) }
    }
    private val transaction by lazy { TransactionTemplate(transactionManager) }

    @BeforeEach
    fun resetClock() {
        clock.currentTime = NOW
        clock.readBarrier = null
    }

    @Test
    fun `수동 접수의 검토 완료는 응답과 같은 전체값 버전 서버 시각을 실제로 커밋한다`() {
        // given
        val receptionId = manualReception()
        val data = reviewedData()

        // when
        val response = service.confirm(ADMIN, receptionId, 1, data)
        val stored = storedReception(receptionId)

        // then
        assertThat(response.receptionId).isEqualTo("rec_" + receptionId)
        assertThat(response.reviewStatus).isEqualTo(ReceptionReviewStatus.CONFIRMED)
        assertThat(response.version).isEqualTo(2)
        assertThat(response.confirmedAt).isEqualTo(NOW)
        assertThat(response.finalData.contactPhone).isEqualTo(data.contactPhone)
        assertThat(stored.finalData.notes).isEqualTo(data.notes)
        assertThat(stored.finalData.contactPhone).isEqualTo(data.contactPhone)
        assertThat(stored.callerPhone).isEqualTo("02-1234-5678")
        assertThat(stored.version).isEqualTo(response.version)
        assertThat(stored.confirmedAt).isEqualTo(response.confirmedAt)
    }

    @ParameterizedTest
    @EnumSource(ReceptionAnalysisStatus::class, names = ["SUCCEEDED", "FAILED", "DISCARDED"])
    fun `서버에서 조회한 분석이 종료되면 전체값을 검토 완료하고 보관된 원본은 보존한다`(status: ReceptionAnalysisStatus) {
        // given
        val receptionId = aiReception(status)
        val before = storedReception(receptionId)
        val analysisBefore = storedAnalysis(before)

        // when
        val response = service.confirm(ADMIN, receptionId, before.version, reviewedData())
        val stored = storedReception(receptionId)
        val analysis = storedAnalysis(stored)

        // then
        assertThat(stored.reviewStatus).isEqualTo(ReceptionReviewStatus.CONFIRMED)
        assertThat(response.version).isEqualTo(before.version + 1)
        assertThat(stored.aiOriginal).isEqualTo(before.aiOriginal)
        assertThat(stored.transcriptFileId).isEqualTo(before.transcriptFileId)
        assertThat(stored.recordingFileId).isEqualTo(before.recordingFileId)
        assertThat(stored.latestAnalysisRequestId).isEqualTo(before.latestAnalysisRequestId)
        assertThat(analysis.status).isEqualTo(analysisBefore.status)
        assertThat(analysis.aiOriginal).isEqualTo(analysisBefore.aiOriginal)
        before.transcriptFileId?.let { transcriptId ->
            assertThat(transcriptRepository.findById(transcriptId).orElseThrow().transcript).isEqualTo(TRANSCRIPT)
        }
    }

    @ParameterizedTest
    @EnumSource(ReceptionAnalysisStatus::class, names = ["PENDING", "PROCESSING"])
    fun `분석 전체가 종료되기 전에는 구조화 단계 성공과 무관하게 검토 완료를 거부한다`(status: ReceptionAnalysisStatus) {
        // given
        // PROCESSING은 구조화 응답 수신 후 접수 적용 직전 상태로 준비한다.
        val receptionId = aiReception(status)
        val before = storedReception(receptionId)

        // when & then
        assertRejected(receptionId, ReceptionErrorCode.REVIEW_NOT_READY) {
            service.confirm(ADMIN, receptionId, before.version, reviewedData())
        }
        assertThat(storedAnalysis(before).status).isEqualTo(status)
    }

    @Test
    fun `녹음 예약만 있는 접수는 분석 시도 부재를 수동 입력 허용으로 취급하지 않는다`() {
        // given
        val receptionId = manualReception()
        transaction.executeWithoutResult {
            val reception = storedReception(receptionId)
            val recording = newRecording(receptionId, verified = false)
            recordingRepository.saveAndFlush(recording)
            reception.reserveRecording(1, checkNotNull(recording.recordingFileId), NOW.minusSeconds(50))
        }

        // when & then
        assertRejected(receptionId, ReceptionErrorCode.REVIEW_NOT_READY) {
            service.confirm(ADMIN, receptionId, 2, reviewedData())
        }
    }

    @ParameterizedTest
    @ValueSource(strings = ["missing-attempt", "other-reception", "other-owner", "other-recording"])
    fun `현재 분석 연결이 누락되거나 다른 문맥을 가리키면 확정을 허용하지 않는다`(invalid: String) {
        // given
        val receptionId = aiReception(ReceptionAnalysisStatus.SUCCEEDED)
        transaction.executeWithoutResult {
            val reception = storedReception(receptionId)
            when (invalid) {
                "missing-attempt" -> ReflectionTestUtils.setField(reception, "latestAnalysisRequestId", id())
                "other-reception" -> {
                    val otherId = aiReception(ReceptionAnalysisStatus.SUCCEEDED)
                    ReflectionTestUtils.setField(reception, "latestAnalysisRequestId", storedReception(otherId).latestAnalysisRequestId)
                }
                "other-owner" -> JdbcTemplate(dataSource).update(
                    "UPDATE $SCHEMA.reception_analysis_requests SET requested_by_admin_id = ? WHERE analysis_request_id = ?",
                    2L,
                    reception.latestAnalysisRequestId,
                )
                "other-recording" -> ReflectionTestUtils.setField(reception, "recordingFileId", id())
            }
        }
        val before = snapshot(storedReception(receptionId))
        val version = storedReception(receptionId).version

        // when
        assertThrows<IllegalStateException> { service.confirm(ADMIN, receptionId, version, reviewedData()) }

        // then
        assertThat(snapshot(storedReception(receptionId))).isEqualTo(before)
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `다른 관리자 접수와 존재하지 않는 접수는 같은 404로 거부하고 아무 값도 변경하지 않는다`(reopen: Boolean) {
        // given
        val receptionId = manualReception(confirmed = reopen)
        val before = snapshot(storedReception(receptionId))
        val nonexistentId = id()

        // when
        val exceptions = listOf(receptionId, nonexistentId).map { target ->
            assertThrows<BusinessException> {
                if (reopen) {
                    service.reopen(2L, target, -1)
                } else {
                    service.confirm(2L, target, -1, reviewedData().copy(symptoms = ""))
                }
            }
        }

        // then
        assertThat(exceptions.map { it.errorCode }).containsOnly(ReceptionErrorCode.RECEPTION_NOT_FOUND)
        assertThat(exceptions.map { it.errorCode.status }).containsOnly(HttpStatus.NOT_FOUND)
        assertThat(exceptions.map { it.message }).containsOnly("접수를 찾을 수 없습니다.")
        assertThat(snapshot(storedReception(receptionId))).isEqualTo(before)
    }

    @Test
    fun `잘못된 전체값이나 과거 방문 종료 시각은 검토 상태와 자동 채움 값을 저장하지 않는다`() {
        // given
        val receptionId = aiReception(ReceptionAnalysisStatus.SUCCEEDED)
        val version = storedReception(receptionId).version
        val invalidInputs = listOf(
            reviewedData().copy(contactPhone = ""),
            reviewedData().copy(desiredVisitTo = NOW.atOffset(ZoneOffset.UTC)),
        )

        // when & then
        invalidInputs.forEach { data ->
            assertRejected(receptionId, GlobalErrorCode.INVALID_INPUT_VALUE) {
                service.confirm(ADMIN, receptionId, version, data)
            }
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `지난 버전으로 확정하거나 취소하면 현재 데이터를 보존한다`(reopen: Boolean) {
        // given
        val receptionId = manualReception(confirmed = reopen)

        // when & then
        assertRejected(receptionId, GlobalErrorCode.VERSION_CONFLICT) {
            if (reopen) service.reopen(ADMIN, receptionId, 1) else service.confirm(ADMIN, receptionId, 2, reviewedData())
        }
    }

    @Test
    fun `확정 취소는 지난 방문 구간과 원본을 보존하고 재확정 때만 새 서버 시각으로 검사한다`() {
        // given
        val receptionId = aiReception(ReceptionAnalysisStatus.SUCCEEDED)
        val initial = storedReception(receptionId)
        val confirmed = service.confirm(ADMIN, receptionId, initial.version, reviewedData())
        clock.currentTime = NOW.plusSeconds(7200)

        // when
        val reopened = service.reopen(ADMIN, receptionId, confirmed.version)

        // then
        assertThat(reopened.reviewStatus).isEqualTo(ReceptionReviewStatus.DRAFT)
        assertThat(reopened.confirmedAt).isNull()
        assertThat(reopened.updatedAt).isEqualTo(clock.currentTime)
        assertThat(reopened.version).isEqualTo(confirmed.version + 1)
        assertThat(reopened.finalData).isEqualTo(confirmed.finalData)
        assertThat(storedReception(receptionId).aiOriginal).isEqualTo(initial.aiOriginal)
        assertThat(storedAnalysis(initial).status).isEqualTo(ReceptionAnalysisStatus.SUCCEEDED)
        assertRejected(receptionId, GlobalErrorCode.INVALID_INPUT_VALUE) {
            service.confirm(ADMIN, receptionId, reopened.version, reviewedData())
        }

        // when
        val revised = reviewedData().copy(
            desiredVisitFrom = clock.currentTime.atOffset(ZoneOffset.UTC),
            desiredVisitTo = clock.currentTime.plusSeconds(3600).atOffset(ZoneOffset.UTC),
            notes = null,
        )
        val reconfirmed = service.confirm(ADMIN, receptionId, reopened.version, revised)

        // then
        assertThat(reconfirmed.confirmedAt).isEqualTo(clock.currentTime)
        assertThat(reconfirmed.finalData.notes).isNull()
        assertThat(storedReception(receptionId).finalData.notes).isNull()
        assertThat(storedReception(receptionId).aiOriginal).isEqualTo(initial.aiOriginal)
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `의뢰로 등록된 접수는 다시 확정하거나 확정 취소할 수 없다`(reopen: Boolean) {
        // given
        val receptionId = manualReception(confirmed = true)
        transaction.executeWithoutResult { ReflectionTestUtils.setField(storedReception(receptionId), "requestId", id()) }

        // when & then
        assertRejected(receptionId, ReceptionErrorCode.RECEPTION_ALREADY_REGISTERED) {
            if (reopen) service.reopen(ADMIN, receptionId, 2) else service.confirm(ADMIN, receptionId, 2, reviewedData())
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `검토 변경을 플러시한 뒤 상위 트랜잭션이 실패하면 변경 전체를 롤백한다`(reopen: Boolean) {
        // given
        val receptionId = manualReception(confirmed = reopen)
        val before = snapshot(storedReception(receptionId))
        val version = storedReception(receptionId).version

        // when
        assertThrows<IllegalStateException> {
            transaction.executeWithoutResult {
                if (reopen) service.reopen(ADMIN, receptionId, version) else service.confirm(ADMIN, receptionId, version, reviewedData())
                error("Simulated failure after the review write.")
            }
        }

        // then
        assertThat(snapshot(storedReception(receptionId))).isEqualTo(before)
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `같은 버전을 읽은 두 확정 또는 취소 요청 중 하나만 커밋하고 충돌을 업무 오류로 변환한다`(reopen: Boolean) {
        // given
        val receptionId = manualReception(confirmed = reopen)
        val version = storedReception(receptionId).version
        // 서비스가 각각 독립 트랜잭션에서 같은 행을 조회한 뒤 시각을 읽는 지점에서 만난다.
        clock.readBarrier = CountDownLatch(2)
        val executor = Executors.newFixedThreadPool(2)

        // when
        val results = try {
            val futures = (1..2).map {
                executor.submit<ErrorCode?> {
                    try {
                        if (reopen) service.reopen(ADMIN, receptionId, version) else service.confirm(ADMIN, receptionId, version, reviewedData())
                        null
                    } catch (exception: BusinessException) {
                        exception.errorCode
                    }
                }
            }
            futures.map { it.get(10, TimeUnit.SECONDS) }
        } finally {
            clock.readBarrier = null
            executor.shutdownNow()
        }

        // then
        assertThat(results).containsExactlyInAnyOrder(null, GlobalErrorCode.VERSION_CONFLICT)
        val stored = storedReception(receptionId)
        assertThat(stored.version).isEqualTo(version + 1)
        assertThat(stored.reviewStatus).isEqualTo(if (reopen) ReceptionReviewStatus.DRAFT else ReceptionReviewStatus.CONFIRMED)
    }

    @AfterAll
    fun removeOwnedTestSchema() {
        JdbcTemplate(dataSource).execute("DROP SCHEMA IF EXISTS $SCHEMA CASCADE")
    }

    private fun assertRejected(receptionId: Long, expected: ErrorCode, action: () -> Unit) {
        val before = snapshot(storedReception(receptionId))
        val exception = assertThrows<BusinessException> { action() }
        assertThat(exception.errorCode).isSameAs(expected)
        assertThat(snapshot(storedReception(receptionId))).isEqualTo(before)
    }

    private fun snapshot(reception: Reception): List<Any?> = listOf(
        reception.version, reception.reviewStatus, reception.confirmedAt, reception.updatedAt,
        reception.finalData, reception.aiOriginal, reception.recordingFileId, reception.transcriptFileId,
        reception.latestAnalysisRequestId, reception.requestId,
    )

    private fun storedReception(receptionId: Long) = receptionRepository.findById(receptionId).orElseThrow()

    private fun storedAnalysis(reception: Reception) = analysisRepository.findById(checkNotNull(reception.latestAnalysisRequestId)).orElseThrow()

    private fun manualReception(confirmed: Boolean = false): Long = checkNotNull(
        transaction.execute {
            val reception = Reception(ADMIN, NOW.minusSeconds(100), "02-1234-5678")
            if (confirmed) reception.confirm(1, reviewedData(), null, NOW.minusSeconds(1))
            receptionRepository.saveAndFlush(reception).receptionId
        },
    )

    /** 검토 서비스의 상태별 검증을 격리하기 위한 저장 문맥. 전체 처리 흐름은 실행 통합 테스트에서 확인한다. */
    private fun aiReception(status: ReceptionAnalysisStatus): Long = checkNotNull(
        transaction.execute {
            val reception = receptionRepository.saveAndFlush(Reception(ADMIN, NOW.minusSeconds(100)))
            val recording = recordingRepository.saveAndFlush(newRecording(checkNotNull(reception.receptionId)))
            reception.reserveRecording(1, checkNotNull(recording.recordingFileId), NOW.minusSeconds(50))
            ReflectionTestUtils.setField(reception, "version", 3)
            val analysis = analysisRepository.saveAndFlush(
                ReceptionAnalysis(checkNotNull(reception.receptionId), checkNotNull(recording.recordingFileId), 3, ADMIN, NOW.minusSeconds(45), NOW.minusSeconds(44)),
            )
            ReflectionTestUtils.setField(reception, "latestAnalysisRequestId", analysis.analysisRequestId)
            if (status == ReceptionAnalysisStatus.PENDING) return@execute reception.receptionId

            analysis.claim(WORKER, NOW.minusSeconds(43), NOW.plusSeconds(60))
            if (status == ReceptionAnalysisStatus.FAILED) {
                analysis.receiveTranscription(WORKER, " ", TRACE, NOW.minusSeconds(42))
                return@execute reception.receptionId
            }
            analysis.receiveTranscription(WORKER, TRANSCRIPT, TRACE, NOW.minusSeconds(42))
            val transcript = transcriptRepository.saveAndFlush(
                ReceptionTranscript(checkNotNull(reception.receptionId), checkNotNull(recording.recordingFileId), checkNotNull(analysis.analysisRequestId), TRANSCRIPT, NOW.minusSeconds(41)),
            )
            analysis.attachTranscript(WORKER, transcript, TRACE, transcript.storedAt)
            ReflectionTestUtils.setField(reception, "transcriptFileId", transcript.transcriptFileId)
            analysis.startStructuring(WORKER, TRACE, NOW.minusSeconds(40))
            analysis.receiveStructuredResult(
                WORKER,
                ReceptionStructuredResult(ReceptionAiOriginal(workType = WorkType.WATERPROOFING, symptoms = "AI 추출 증상", notes = "AI 원본 메모"), emptyList()),
                TRACE,
                NOW.minusSeconds(39),
            )
            if (status == ReceptionAnalysisStatus.PROCESSING) return@execute reception.receptionId
            if (status == ReceptionAnalysisStatus.DISCARDED) ReflectionTestUtils.setField(reception, "version", 4)
            analysis.applyResultTo(reception, WORKER, TRACE, NOW.minusSeconds(38))
            reception.receptionId
        },
    )

    private fun newRecording(receptionId: Long, verified: Boolean = true) = ReceptionRecording(
        receptionId, ADMIN, "call.m4a", "test-bucket", "recordings/" + id(),
        "audio/mp4", 1024, ByteArray(32) { 1 }, NOW.minusSeconds(50),
    ).also { if (verified) it.completeUpload(it.contentType, it.sizeBytes, it.checksumSha256, NOW.minusSeconds(45)) }

    private fun reviewedData() = ReceptionConfirmationData(
        WorkType.BOILER,
        "관리자 수정 증상",
        "서울시 강남구 예시로 10",
        OffsetDateTime.parse("2026-11-17T14:00:00+09:00"),
        OffsetDateTime.parse("2026-11-17T16:00:00+09:00"),
        "010-1234-5678",
        "방문 전 연락",
    )

    private val nextId = java.util.concurrent.atomic.AtomicLong(1_000_000)

    private fun id() = nextId.incrementAndGet()

    @TestConfiguration(proxyBeanMethods = false)
    class TestTimeConfig {
        @Bean
        fun clock() = ControlledClock()
    }

    class ControlledClock : Clock() {
        @Volatile
        var currentTime: Instant = NOW

        @Volatile
        var readBarrier: CountDownLatch? = null

        override fun instant(): Instant {
            readBarrier?.let { barrier ->
                barrier.countDown()
                check(barrier.await(5, TimeUnit.SECONDS)) { "Concurrent service calls did not both read the reception." }
            }
            return currentTime
        }

        override fun getZone(): ZoneId = ZoneOffset.UTC

        override fun withZone(zone: ZoneId): Clock = fixed(currentTime, zone)
    }

    companion object {
        private val NOW = Instant.parse("2026-11-17T06:00:00Z")
        private const val ADMIN = 1L
        private const val WORKER = "test-worker"
        private const val TRACE = "test-trace"
        private const val TRANSCRIPT = "고객: 방문 전 연락 주세요.\r\n관리자: 확인했습니다. 😀"
        private val SCHEMA = "reception_service_test_" + UUID.randomUUID().toString().replace("-", "")

        @JvmStatic
        @DynamicPropertySource
        fun testDatabase(registry: DynamicPropertyRegistry) {
            val url = System.getenv("TEST_DATABASE_URL") ?: "jdbc:postgresql://localhost:15432/allcheck"
            registry.add("spring.datasource.url") { url + (if ('?' in url) "&" else "?") + "currentSchema=$SCHEMA" }
            registry.add("spring.datasource.username") { System.getenv("TEST_DATABASE_USERNAME") ?: "allcheck" }
            registry.add("spring.datasource.password") { System.getenv("TEST_DATABASE_PASSWORD") ?: "allcheck_local_6" }
            registry.add("spring.flyway.schemas") { SCHEMA }
            registry.add("spring.flyway.default-schema") { SCHEMA }
            registry.add("spring.jpa.properties.hibernate.default_schema") { SCHEMA }
        }
    }
}
