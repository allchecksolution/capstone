package com.allcheck.reception.infrastructure.repository

import com.allcheck.global.config.HibernateConfig
import com.allcheck.reception.domain.entity.ReceptionAnalysis
import com.allcheck.reception.domain.entity.ReceptionRecording
import com.allcheck.reception.domain.entity.ReceptionTranscript
import com.allcheck.reception.domain.vo.ReceptionAnalysisStage
import com.allcheck.reception.domain.vo.ReceptionAnalysisStatus
import com.allcheck.reception.domain.vo.ReceptionAnalysisTransition
import com.allcheck.reception.domain.vo.ReceptionProcessingStatus
import com.allcheck.reception.domain.vo.WorkType
import jakarta.persistence.EntityManager
import org.assertj.core.api.Assertions.assertThat
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.autoconfigure.ImportAutoConfiguration
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest
import org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase
import org.springframework.context.annotation.Import
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.dao.OptimisticLockingFailureException
import org.springframework.data.domain.PageRequest
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
import java.sql.SQLException
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.sql.DataSource

@DataJpaTest(properties = ["spring.jpa.hibernate.ddl-auto=validate", "spring.jpa.show-sql=false"])
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ImportAutoConfiguration(FlywayAutoConfiguration::class)
@Import(HibernateConfig::class)
@ActiveProfiles("test")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class ReceptionAnalysisRepositoryTest @Autowired constructor(
    private val repository: ReceptionAnalysisRepository,
    private val recordingRepository: ReceptionRecordingRepository,
    private val transcriptRepository: ReceptionTranscriptRepository,
    private val entityManager: EntityManager,
    private val dataSource: DataSource,
    private val transactionManager: PlatformTransactionManager,
) {
    private val jdbc by lazy { JdbcTemplate(dataSource) }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    fun `원문 본문과 분석 연결 및 보관 성공 상태를 함께 커밋한다`() {
        // given
        val transaction = TransactionTemplate(transactionManager)
        val analysis = transaction.execute { repository.saveAndFlush(awaitingStorage()) }
        val artifact = transcript(analysis)

        // when
        transaction.executeWithoutResult {
            val current = repository.findById(analysis.analysisRequestId).orElseThrow()
            assertThat(current.attachTranscript(WORKER, artifact, TRACE, STORED_AT)).isEqualTo(ReceptionAnalysisTransition.ADVANCED)
            transcriptRepository.saveAndFlush(artifact)
        }
        val saved = repository.findById(analysis.analysisRequestId).orElseThrow()
        val original = transcriptRepository.findByProducedByAnalysisRequestId(analysis.analysisRequestId)
            ?: error("Canonical original must be stored")

        // then
        assertThat(saved.stage).isEqualTo(ReceptionAnalysisStage.STRUCTURE)
        assertThat(saved.status).isEqualTo(ReceptionAnalysisStatus.PROCESSING)
        assertThat(saved.sttStatus).isEqualTo(ReceptionProcessingStatus.SUCCEEDED)
        assertThat(saved.transcriptStorageStatus).isEqualTo(ReceptionProcessingStatus.SUCCEEDED)
        assertThat(saved.analysisStatus).isEqualTo(ReceptionProcessingStatus.PENDING)
        assertThat(saved.transcriptFileId).isEqualTo(original.transcriptFileId)
        assertThat(original.transcript).isEqualTo(ORIGINAL)
        assertThat(original.storedAt).isEqualTo(STORED_AT)
        assertThat(saved.receptionVersion).isEqualTo(3)
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    fun `원문 저장 실패는 연결까지 롤백하고 별도 실패 기록에서 STT 성공을 유지한다`() {
        // given
        val transaction = TransactionTemplate(transactionManager)
        val existing = transaction.execute {
            val producer = repository.saveAndFlush(awaitingStorage())
            val original = transcript(producer)
            assertThat(producer.attachTranscript(WORKER, original, TRACE, STORED_AT)).isEqualTo(ReceptionAnalysisTransition.ADVANCED)
            transcriptRepository.saveAndFlush(original)
        }
        val analysis = transaction.execute { repository.saveAndFlush(awaitingStorage()) }
        val colliding = transcript(analysis, existing.transcriptFileId)

        // when
        assertThrows<DataIntegrityViolationException> {
            transaction.executeWithoutResult {
                val current = repository.findById(analysis.analysisRequestId).orElseThrow()
                assertThat(current.attachTranscript(WORKER, colliding, TRACE, STORED_AT)).isEqualTo(ReceptionAnalysisTransition.ADVANCED)
                transcriptRepository.saveAndFlush(colliding)
            }
        }
        val afterRollback = repository.findById(analysis.analysisRequestId).orElseThrow()

        // then
        assertThat(afterRollback.stage).isEqualTo(ReceptionAnalysisStage.TRANSCRIPT_STORAGE)
        assertThat(afterRollback.sttStatus).isEqualTo(ReceptionProcessingStatus.SUCCEEDED)
        assertThat(afterRollback.transcriptStorageStatus).isEqualTo(ReceptionProcessingStatus.PROCESSING)
        assertThat(afterRollback.transcriptFileId).isNull()
        assertThat(transcriptRepository.findByProducedByAnalysisRequestId(analysis.analysisRequestId)).isNull()

        // when
        transaction.executeWithoutResult {
            val current = repository.findById(analysis.analysisRequestId).orElseThrow()
            assertThat(current.failTranscriptStorage(WORKER, TRACE, STORED_AT.plusSeconds(1))).isTrue()
        }
        val failed = repository.findById(analysis.analysisRequestId).orElseThrow()

        // then
        assertThat(failed.status).isEqualTo(ReceptionAnalysisStatus.FAILED)
        assertThat(failed.errorCode).isEqualTo("TRANSCRIPT_STORAGE_FAILED")
        assertThat(failed.sttStatus).isEqualTo(ReceptionProcessingStatus.SUCCEEDED)
        assertThat(failed.transcriptStorageStatus).isEqualTo(ReceptionProcessingStatus.FAILED)
        assertThat(failed.analysisStatus).isEqualTo(ReceptionProcessingStatus.NOT_AVAILABLE)
        assertThat(failed.transcriptFileId).isNull()
        assertThat(failed.claimedBy).isNull()
        assertThat(failed.leaseExpiresAt).isNull()
        assertThat(transcriptRepository.findById(existing.transcriptFileId).orElseThrow().transcript).isEqualTo(ORIGINAL)
    }

    @Test
    fun `빈 STT 결과의 최초 실패와 시각을 저장하고 늦은 결과는 DB를 변경하지 않는다`() {
        // given
        val analysis = analysis().also { it.claim(WORKER, STARTED_AT, DEADLINE) }
        repository.saveAndFlush(analysis)

        // when
        assertThat(analysis.receiveTranscription(WORKER, " \r\n\u00A0", TRACE, TRANSCRIBED_AT)).isEqualTo(ReceptionAnalysisTransition.FAILED)
        repository.flush()
        entityManager.clear()
        val failed = repository.findById(analysis.analysisRequestId).orElseThrow()
        assertThat(failed.receiveTranscription(WORKER, ORIGINAL, "late-trace", DEADLINE)).isEqualTo(ReceptionAnalysisTransition.IGNORED)
        assertThat(failed.expireIfDue(DEADLINE, "late-trace")).isFalse()
        repository.flush()
        entityManager.clear()
        val saved = repository.findById(analysis.analysisRequestId).orElseThrow()

        // then
        assertThat(saved.status).isEqualTo(ReceptionAnalysisStatus.FAILED)
        assertThat(saved.errorCode).isEqualTo("STT_NO_CONTENT")
        assertThat(saved.traceId).isEqualTo(TRACE)
        assertThat(saved.stage).isEqualTo(ReceptionAnalysisStage.STT)
        assertThat(saved.sttStatus).isEqualTo(ReceptionProcessingStatus.FAILED)
        assertThat(saved.transcriptStorageStatus).isEqualTo(ReceptionProcessingStatus.NOT_AVAILABLE)
        assertThat(saved.analysisStatus).isEqualTo(ReceptionProcessingStatus.NOT_AVAILABLE)
        assertThat(saved.completedAt).isEqualTo(TRANSCRIBED_AT)
        assertThat(saved.transcriptFileId).isNull()
        assertThat(transcriptRepository.findByProducedByAnalysisRequestId(saved.analysisRequestId)).isNull()
        assertThat(persistenceVersion(saved.analysisRequestId)).isEqualTo(1)
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    fun `원문 보관 뒤 기한이 만료되어도 원문과 앞 단계 성공을 유지한다`() {
        // given
        val transaction = TransactionTemplate(transactionManager)
        val analysis = transaction.execute {
            val current = repository.saveAndFlush(awaitingStorage())
            val artifact = transcript(current)
            assertThat(current.attachTranscript(WORKER, artifact, TRACE, STORED_AT)).isEqualTo(ReceptionAnalysisTransition.ADVANCED)
            transcriptRepository.saveAndFlush(artifact)
            current
        }

        // when
        transaction.executeWithoutResult {
            val current = repository.findById(analysis.analysisRequestId).orElseThrow()
            assertThat(current.expireIfDue(DEADLINE, TRACE)).isTrue()
        }
        val failed = repository.findById(analysis.analysisRequestId).orElseThrow()
        val original = transcriptRepository.findById(checkNotNull(failed.transcriptFileId)).orElseThrow()

        // then
        assertThat(failed.status).isEqualTo(ReceptionAnalysisStatus.FAILED)
        assertThat(failed.stage).isEqualTo(ReceptionAnalysisStage.STRUCTURE)
        assertThat(failed.errorCode).isEqualTo("ANALYSIS_TIMEOUT")
        assertThat(failed.sttStatus).isEqualTo(ReceptionProcessingStatus.SUCCEEDED)
        assertThat(failed.transcriptStorageStatus).isEqualTo(ReceptionProcessingStatus.SUCCEEDED)
        assertThat(failed.analysisStatus).isEqualTo(ReceptionProcessingStatus.FAILED)
        assertThat(original.transcript).isEqualTo(ORIGINAL)
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    fun `원문 저장과 만료가 경합해도 패자의 원문 행이나 분석 연결을 남기지 않는다`() {
        // given
        val transaction = TransactionTemplate(transactionManager)
        val analysis = transaction.execute { repository.saveAndFlush(awaitingStorage()) }
        val artifact = transcript(analysis)
        val loaded = CountDownLatch(2)
        val executor = Executors.newFixedThreadPool(2)

        try {
            // when
            val futures = listOf("store", "expire").map { action ->
                action to executor.submit<Boolean> {
                    try {
                        transaction.executeWithoutResult {
                            val current = repository.findById(analysis.analysisRequestId).orElseThrow()
                            loaded.countDown()
                            check(loaded.await(10, TimeUnit.SECONDS)) { "Both transactions must read before transitioning." }
                            if (action == "store") {
                                check(current.attachTranscript(WORKER, artifact, TRACE, STORED_AT) == ReceptionAnalysisTransition.ADVANCED)
                                transcriptRepository.saveAndFlush(artifact)
                            } else {
                                check(current.expireIfDue(DEADLINE, TRACE))
                                repository.flush()
                            }
                        }
                        true
                    } catch (_: OptimisticLockingFailureException) {
                        false
                    }
                }
            }
            val results = futures.map { (action, future) -> action to future.get(15, TimeUnit.SECONDS) }
            val winner = results.single { it.second }.first
            val saved = repository.findById(analysis.analysisRequestId).orElseThrow()
            val original = transcriptRepository.findByProducedByAnalysisRequestId(analysis.analysisRequestId)

            // then
            assertThat(results.count { it.second }).isEqualTo(1)
            if (winner == "store") {
                assertThat(saved.status).isEqualTo(ReceptionAnalysisStatus.PROCESSING)
                assertThat(saved.transcriptFileId).isEqualTo(artifact.transcriptFileId)
                assertThat(original?.transcript).isEqualTo(ORIGINAL)
            } else {
                assertThat(saved.status).isEqualTo(ReceptionAnalysisStatus.FAILED)
                assertThat(saved.errorCode).isEqualTo("ANALYSIS_TIMEOUT")
                assertThat(saved.transcriptFileId).isNull()
                assertThat(original).isNull()
            }
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun `최초 실행 의도를 고정 문맥과 함께 보관하고 접수가 일치할 때 조회한다`() {
        // given
        val analysis = analysis()

        // when
        repository.saveAndFlush(analysis)
        entityManager.clear()
        val saved = repository.findByAnalysisRequestIdAndReceptionId(analysis.analysisRequestId, analysis.receptionId)
            ?: error("Pending intent must be found")

        // then
        assertThat(saved.recordingFileId).isEqualTo(analysis.recordingFileId)
        assertThat(saved.receptionVersion).isEqualTo(3)
        assertThat(saved.referenceTime).isEqualTo(REFERENCE_TIME)
        assertThat(saved.requestedByAdminId).isEqualTo("test-admin")
        assertThat(saved.timeZone).isEqualTo("Asia/Seoul")
        assertThat(saved.mode).isEqualTo("FULL")
        assertThat(saved.isInitial).isTrue()
        assertThat(saved.status).isEqualTo(ReceptionAnalysisStatus.PENDING)
        assertThat(saved.stage).isEqualTo(ReceptionAnalysisStage.QUEUED)
        assertThat(saved.sttStatus).isEqualTo(ReceptionProcessingStatus.PENDING)
        assertThat(saved.transcriptStorageStatus).isEqualTo(ReceptionProcessingStatus.PENDING)
        assertThat(saved.analysisStatus).isEqualTo(ReceptionProcessingStatus.PENDING)
        assertThat(saved.transcriptFileId).isNull()
        assertThat(saved.aiOriginal).isNull()
        assertThat(saved.missingFields).isNull()
        assertThat(saved.createdAt).isEqualTo(ACCEPTED_AT)
        assertThat(saved.updatedAt).isEqualTo(ACCEPTED_AT)
        assertThat(persistenceVersion(saved.analysisRequestId)).isZero()
        assertThat(repository.findByRecordingFileIdAndIsInitialTrue(analysis.recordingFileId)?.analysisRequestId)
            .isEqualTo(analysis.analysisRequestId)
        assertThat(repository.findByAnalysisRequestIdAndReceptionId(analysis.analysisRequestId, id())).isNull()
    }

    @Test
    fun `실행권을 저장한 뒤 중복 점유는 시작 시각과 DB 수정 버전을 바꾸지 않는다`() {
        // given
        val analysis = repository.saveAndFlush(analysis())
        assertThat(analysis.claim("worker-1", STARTED_AT, DEADLINE)).isTrue()
        repository.flush()
        entityManager.clear()
        val claimed = repository.findById(analysis.analysisRequestId).orElseThrow()

        // when
        val claimedAgain = claimed.claim("worker-2", DEADLINE.plusSeconds(1), DEADLINE.plusSeconds(60))
        repository.flush()
        entityManager.clear()
        val saved = repository.findById(analysis.analysisRequestId).orElseThrow()

        // then
        assertThat(claimedAgain).isFalse()
        assertThat(saved.status).isEqualTo(ReceptionAnalysisStatus.PROCESSING)
        assertThat(saved.stage).isEqualTo(ReceptionAnalysisStage.STT)
        assertThat(saved.sttStatus).isEqualTo(ReceptionProcessingStatus.PROCESSING)
        assertThat(saved.claimedBy).isEqualTo("worker-1")
        assertThat(saved.startedAt).isEqualTo(STARTED_AT)
        assertThat(saved.updatedAt).isEqualTo(STARTED_AT)
        assertThat(saved.leaseExpiresAt).isEqualTo(DEADLINE)
        assertThat(saved.receptionVersion).isEqualTo(3)
        assertThat(saved.referenceTime).isEqualTo(REFERENCE_TIME)
        assertThat(persistenceVersion(saved.analysisRequestId)).isEqualTo(1)
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    fun `독립 트랜잭션의 동시 점유는 작업자 한 명만 커밋한다`() {
        // given
        val transaction = TransactionTemplate(transactionManager)
        val analysis = transaction.execute { repository.saveAndFlush(analysis()) }
        val loaded = CountDownLatch(2)
        val executor = Executors.newFixedThreadPool(2)

        try {
            // when
            val futures = listOf("worker-1", "worker-2").map { worker ->
                worker to executor.submit<Boolean> {
                    try {
                        transaction.execute {
                            val current = repository.findById(analysis.analysisRequestId).orElseThrow()
                            loaded.countDown()
                            check(loaded.await(10, TimeUnit.SECONDS)) { "Both transactions must read before claiming." }
                            val claimed = current.claim(worker, STARTED_AT, DEADLINE)
                            repository.flush()
                            claimed
                        }
                    } catch (_: OptimisticLockingFailureException) {
                        false
                    }
                }
            }
            val results = futures.map { (worker, future) -> worker to future.get(15, TimeUnit.SECONDS) }
            val winner = results.single { it.second }.first
            val saved = repository.findById(analysis.analysisRequestId).orElseThrow()

            // then
            assertThat(results.count { it.second }).isEqualTo(1)
            assertThat(saved.claimedBy).isEqualTo(winner)
            assertThat(saved.status).isEqualTo(ReceptionAnalysisStatus.PROCESSING)
            assertThat(saved.startedAt).isEqualTo(STARTED_AT)
            assertThat(saved.leaseExpiresAt).isEqualTo(DEADLINE)
            assertThat(saved.receptionVersion).isEqualTo(3)
            assertThat(persistenceVersion(saved.analysisRequestId)).isEqualTo(1)
        } finally {
            executor.shutdownNow()
        }
    }

    @ParameterizedTest
    @ValueSource(strings = ["PENDING", "FAILED"])
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    fun `대기 또는 실패 종료된 녹음에도 두 번째 최초 분석을 만들지 않는다`(state: String) {
        // given
        val transaction = TransactionTemplate(transactionManager)
        val first = transaction.execute { repository.saveAndFlush(analysis()) }
        if (state == "FAILED") {
            // 실패 전환 구현과 독립적으로 기존 저장 상태를 준비한다.
            jdbc.update(
                """
                UPDATE $SCHEMA.reception_analysis_requests
                SET status = 'FAILED', stage = 'STT', stt_status = 'FAILED',
                    transcript_storage_status = 'NOT_AVAILABLE', analysis_status = 'NOT_AVAILABLE',
                    completed_at = ?, updated_at = ?, error_code = 'STT_NO_CONTENT',
                    error_message = '전사 내용 없음', trace_id = 'test-trace'
                WHERE analysis_request_id = ?
                """.trimIndent(),
                STARTED_AT.atOffset(ZoneOffset.UTC),
                STARTED_AT.atOffset(ZoneOffset.UTC),
                first.analysisRequestId,
            )
        }
        val duplicate = ReceptionAnalysis(
            analysisRequestId = id(),
            receptionId = first.receptionId,
            recordingFileId = first.recordingFileId,
            receptionVersion = 3,
            requestedByAdminId = "test-admin",
            referenceTime = REFERENCE_TIME,
            createdAt = ACCEPTED_AT,
        )

        // when
        val exception = assertThrows<DataIntegrityViolationException> {
            transaction.executeWithoutResult { repository.saveAndFlush(duplicate) }
        }
        val saved = repository.findByRecordingFileIdAndIsInitialTrue(first.recordingFileId) ?: error("First intent must remain")

        // then
        assertSqlState(exception, "23505")
        assertThat(saved.analysisRequestId).isEqualTo(first.analysisRequestId)
        assertThat(saved.status.name).isEqualTo(state)
        assertThat(saved.referenceTime).isEqualTo(REFERENCE_TIME)
        assertThat(saved.receptionVersion).isEqualTo(3)
    }

    @ParameterizedTest
    @ValueSource(strings = ["reception", "recording", "reference", "unverified"])
    fun `다른 접수 녹음 수신 시각 또는 미검증 원본은 분석에 연결하지 않는다`(mismatch: String) {
        // given
        val recording = recording(verified = mismatch != "unverified")
        val invalid = ReceptionAnalysis(
            analysisRequestId = id(),
            receptionId = if (mismatch == "reception") id() else recording.receptionId,
            recordingFileId = if (mismatch == "recording") id() else recording.recordingFileId,
            receptionVersion = 3,
            requestedByAdminId = "test-admin",
            referenceTime = if (mismatch == "reference") REFERENCE_TIME.minusSeconds(1) else REFERENCE_TIME,
            createdAt = ACCEPTED_AT,
        )

        // when
        val exception = assertThrows<DataIntegrityViolationException> { repository.saveAndFlush(invalid) }

        // then
        assertSqlState(exception, "23503")
    }

    @Test
    fun `대기 목록은 최초 분석만 접수 시각과 ID 순서로 페이지 조회한다`() {
        // given
        val earlierB = analysis("analysis-b")
        val earlierA = analysis("analysis-a")
        val later = analysis("analysis-later", ACCEPTED_AT.plusSeconds(1))
        val processing = analysis().also { it.claim("worker-1", STARTED_AT, DEADLINE) }
        val reservedNoninitial = analysis("analysis-noninitial").also { ReflectionTestUtils.setField(it, "isInitial", false) }
        repository.saveAllAndFlush(listOf(earlierB, later, earlierA, processing, reservedNoninitial))
        entityManager.clear()

        // when
        val firstPage = repository.findAllByStatusAndIsInitialTrueOrderByCreatedAtAscAnalysisRequestIdAsc(
            ReceptionAnalysisStatus.PENDING,
            PageRequest.of(0, 2),
        )
        val secondPage = repository.findAllByStatusAndIsInitialTrueOrderByCreatedAtAscAnalysisRequestIdAsc(
            ReceptionAnalysisStatus.PENDING,
            PageRequest.of(1, 2),
        )

        // then
        assertThat(firstPage.map { it.analysisRequestId }).containsExactly("analysis-a", "analysis-b")
        assertThat(secondPage.map { it.analysisRequestId }).containsExactly("analysis-later")
        assertThat(repository.findById(earlierA.analysisRequestId).orElseThrow().status).isEqualTo(ReceptionAnalysisStatus.PENDING)
        assertThat(repository.findByRecordingFileIdAndIsInitialTrue(reservedNoninitial.recordingFileId)).isNull()
    }

    @Test
    fun `기존 성공 기록의 원본 JSON과 단계 결과를 조회할 수 있다`() {
        // given
        val analysis = repository.saveAndFlush(analysis())
        val transcript = transcriptRepository.saveAndFlush(
            ReceptionTranscript(id(), analysis.receptionId, analysis.recordingFileId, analysis.analysisRequestId, "보일러 누수", STARTED_AT),
        )
        jdbc.update(
            """
            UPDATE $SCHEMA.reception_analysis_requests
            SET status = 'SUCCEEDED', stage = 'COMPLETED', stt_status = 'SUCCEEDED',
                transcript_storage_status = 'SUCCEEDED', analysis_status = 'SUCCEEDED', result_applied = TRUE,
                transcript_file_id = ?, started_at = ?, completed_at = ?, updated_at = ?,
                ai_original = '{"workType":"BOILER","desiredVisitFrom":"2026-11-17T14:00:00+09:00","notes":"방문 전 연락"}'::jsonb,
                missing_fields = '["address","desiredVisitTo"]'::jsonb
            WHERE analysis_request_id = ?
            """.trimIndent(),
            transcript.transcriptFileId,
            STARTED_AT.atOffset(ZoneOffset.UTC),
            DEADLINE.atOffset(ZoneOffset.UTC),
            DEADLINE.atOffset(ZoneOffset.UTC),
            analysis.analysisRequestId,
        )
        entityManager.clear()

        // when
        val saved = repository.findById(analysis.analysisRequestId).orElseThrow()

        // then
        assertThat(saved.status).isEqualTo(ReceptionAnalysisStatus.SUCCEEDED)
        assertThat(saved.stage).isEqualTo(ReceptionAnalysisStage.COMPLETED)
        assertThat(saved.transcriptFileId).isEqualTo(transcript.transcriptFileId)
        assertThat(saved.resultApplied).isTrue()
        assertThat(saved.aiOriginal?.workType).isEqualTo(WorkType.BOILER)
        assertThat(saved.aiOriginal?.desiredVisitFrom).isEqualTo(OffsetDateTime.parse("2026-11-17T14:00:00+09:00"))
        assertThat(saved.aiOriginal?.notes).isEqualTo("방문 전 연락")
        assertThat(saved.missingFields).containsExactly("address", "desiredVisitTo")
        assertThat(saved.claim("worker-2", DEADLINE.plusSeconds(1), DEADLINE.plusSeconds(60))).isFalse()
    }

    @ParameterizedTest
    @ValueSource(strings = ["negative-version", "invalid-deadline"])
    fun `DB는 잘못된 수정 버전과 실행 기한을 거부한다`(invalid: String) {
        // given
        val analysis = analysis().also { it.claim("worker-1", STARTED_AT, DEADLINE) }
        repository.saveAndFlush(analysis)
        val assignment = if (invalid == "negative-version") "persistence_version = -1" else "lease_expires_at = started_at"

        // when
        val exception = assertThrows<DataIntegrityViolationException> {
            jdbc.update("UPDATE $SCHEMA.reception_analysis_requests SET $assignment WHERE analysis_request_id = ?", analysis.analysisRequestId)
        }

        // then
        assertSqlState(exception, "23514")
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    fun `V6는 기존 진행 시도의 작업자와 기한 및 고정 문맥을 보존한다`() {
        // given
        migrationAt("5").migrate()
        jdbc.update(
            """
            INSERT INTO $UPGRADE_SCHEMA.reception_recordings (
                recording_file_id, reception_id, uploaded_by_admin_id, original_filename,
                bucket, object_key, content_type, size_bytes, checksum_sha256,
                status, recording_received_at, created_at, updated_at
            ) VALUES ('legacy-recording', 'legacy-reception', 'test-admin', 'call.m4a', 'test-bucket',
                'legacy/recording', 'audio/mp4', 1, decode(repeat('00', 32), 'hex'), 'UPLOADED', ?, ?, ?)
            """.trimIndent(),
            REFERENCE_TIME.atOffset(ZoneOffset.UTC),
            REFERENCE_TIME.minusSeconds(1).atOffset(ZoneOffset.UTC),
            REFERENCE_TIME.atOffset(ZoneOffset.UTC),
        )
        jdbc.update(
            """
            INSERT INTO $UPGRADE_SCHEMA.reception_analysis_requests (
                analysis_request_id, reception_id, recording_file_id, reception_version, requested_by_admin_id,
                mode, is_initial, reference_time, status, stage, stt_status,
                claimed_by, lease_expires_at, started_at, created_at, updated_at
            ) VALUES ('legacy-analysis', 'legacy-reception', 'legacy-recording', 3, 'test-admin',
                'FULL', TRUE, ?, 'PROCESSING', 'STT', 'PROCESSING', 'legacy-worker', ?, ?, ?, ?)
            """.trimIndent(),
            REFERENCE_TIME.atOffset(ZoneOffset.UTC),
            DEADLINE.atOffset(ZoneOffset.UTC),
            STARTED_AT.atOffset(ZoneOffset.UTC),
            ACCEPTED_AT.atOffset(ZoneOffset.UTC),
            STARTED_AT.atOffset(ZoneOffset.UTC),
        )

        // when
        val upgraded = migrationAt("6")
        upgraded.migrate()

        // then
        assertThat(upgraded.validateWithResult().validationSuccessful).isTrue()
        assertThat(upgraded.info().current()?.version?.version).isEqualTo("6")
        jdbc.queryForObject("SELECT * FROM $UPGRADE_SCHEMA.reception_analysis_requests") { row, _ ->
            assertThat(row.getString("status")).isEqualTo("PROCESSING")
            assertThat(row.getString("stage")).isEqualTo("STT")
            assertThat(row.getString("stt_status")).isEqualTo("PROCESSING")
            assertThat(row.getString("claimed_by")).isEqualTo("legacy-worker")
            assertThat(row.getInt("reception_version")).isEqualTo(3)
            assertThat(row.getObject("reference_time", OffsetDateTime::class.java).toInstant()).isEqualTo(REFERENCE_TIME)
            assertThat(row.getObject("lease_expires_at", OffsetDateTime::class.java).toInstant()).isEqualTo(DEADLINE)
            assertThat(row.getObject("started_at", OffsetDateTime::class.java).toInstant()).isEqualTo(STARTED_AT)
            assertThat(row.getLong("persistence_version")).isZero()
            row.getString("recording_file_id")
        }.also { assertThat(it).isEqualTo("legacy-recording") }
    }

    @AfterAll
    fun removeOwnedTestSchemas() {
        jdbc.execute("DROP SCHEMA IF EXISTS $UPGRADE_SCHEMA CASCADE")
        jdbc.execute("DROP SCHEMA IF EXISTS $SCHEMA CASCADE")
    }

    private fun recording(verified: Boolean = true) = ReceptionRecording(
        recordingFileId = id(),
        receptionId = id(),
        uploadedByAdminId = "test-admin",
        originalFilename = "call.m4a",
        bucket = "test-bucket",
        objectKey = "recordings/" + id(),
        contentType = "audio/mp4",
        sizeBytes = 1024,
        checksum = ByteArray(32) { 1 },
        createdAt = REFERENCE_TIME.minusSeconds(1),
    ).also {
        if (verified) it.completeUpload(it.contentType, it.sizeBytes, it.checksumSha256, REFERENCE_TIME)
        recordingRepository.saveAndFlush(it)
    }

    private fun analysis(analysisId: String = id(), createdAt: Instant = ACCEPTED_AT): ReceptionAnalysis {
        val recording = recording()
        return ReceptionAnalysis(
            analysisRequestId = analysisId,
            receptionId = recording.receptionId,
            recordingFileId = recording.recordingFileId,
            receptionVersion = 3,
            requestedByAdminId = "test-admin",
            referenceTime = REFERENCE_TIME,
            createdAt = createdAt,
        )
    }

    private fun awaitingStorage() = analysis().also {
        check(it.claim(WORKER, STARTED_AT, DEADLINE))
        check(it.receiveTranscription(WORKER, ORIGINAL, TRACE, TRANSCRIBED_AT) == ReceptionAnalysisTransition.ADVANCED)
    }

    private fun transcript(analysis: ReceptionAnalysis, fileId: String = id()) = ReceptionTranscript(
        transcriptFileId = fileId,
        receptionId = analysis.receptionId,
        recordingFileId = analysis.recordingFileId,
        producedByAnalysisRequestId = analysis.analysisRequestId,
        transcript = ORIGINAL,
        storedAt = STORED_AT,
    )

    private fun assertSqlState(exception: DataIntegrityViolationException, state: String) {
        assertThat(exception.mostSpecificCause).isInstanceOfSatisfying(SQLException::class.java) {
            assertThat(it.sqlState).isEqualTo(state)
        }
    }

    private fun persistenceVersion(analysisId: String) = jdbc.queryForObject(
        "SELECT persistence_version FROM $SCHEMA.reception_analysis_requests WHERE analysis_request_id = ?",
        Long::class.java,
        analysisId,
    )

    private fun migrationAt(version: String) = Flyway.configure()
        .dataSource(dataSource)
        .schemas(UPGRADE_SCHEMA)
        .defaultSchema(UPGRADE_SCHEMA)
        .target(version)
        .load()

    private fun id() = UUID.randomUUID().toString()

    companion object {
        private const val WORKER = "worker-1"
        private const val TRACE = "test-trace"
        private const val ORIGINAL = "  고객: 물이 샙니다.\r\n관리자: 방문하겠습니다.\n고객: 🙂 감사합니다.  "
        private val REFERENCE_TIME = Instant.parse("2026-11-16T00:00:00Z")
        private val ACCEPTED_AT = REFERENCE_TIME.plusSeconds(1)
        private val STARTED_AT = ACCEPTED_AT.plusSeconds(1)
        private val TRANSCRIBED_AT = STARTED_AT.plusSeconds(1)
        private val STORED_AT = TRANSCRIBED_AT.plusSeconds(1)
        private val DEADLINE = STARTED_AT.plusSeconds(60)
        private val SCHEMA = "analysis_test_" + UUID.randomUUID().toString().replace("-", "")
        private val UPGRADE_SCHEMA = SCHEMA + "_upgrade"

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
