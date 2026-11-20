package com.allcheck.reception.infrastructure.repository.analysis

import com.allcheck.global.config.HibernateConfig
import com.allcheck.reception.domain.entity.analysis.ReceptionTranscript
import jakarta.persistence.EntityManager
import org.assertj.core.api.Assertions.assertThat
import org.flywaydb.core.Flyway
import org.flywaydb.core.api.FlywayException
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
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.annotation.DirtiesContext
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.support.TransactionTemplate
import java.security.MessageDigest
import java.sql.SQLException
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID
import javax.sql.DataSource

@DataJpaTest(properties = ["spring.jpa.hibernate.ddl-auto=validate", "spring.jpa.show-sql=false"])
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ImportAutoConfiguration(FlywayAutoConfiguration::class)
@Import(HibernateConfig::class)
@ActiveProfiles("test")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class ReceptionTranscriptRepositoryTest @Autowired constructor(
    private val repository: ReceptionTranscriptRepository,
    private val entityManager: EntityManager,
    private val dataSource: DataSource,
    private val transactionManager: PlatformTransactionManager,
) {
    private val jdbc by lazy { JdbcTemplate(dataSource) }

    @Test
    fun `원문을 S3 위치 없이 DB에 저장하고 문자열과 보관 시각을 그대로 조회한다`() {
        // given
        val source = source()
        val original = "  고객: 물이 샙니다.\r\n관리자: 내일 방문하겠습니다.\n\t고객: 🙂 감사합니다.  \r\n"
        val transcript = transcript(source, original)

        // when
        repository.saveAndFlush(transcript)
        entityManager.clear()
        val saved = repository.findByTranscriptFileIdAndRecordingFileIdAndReceptionId(
            checkNotNull(transcript.transcriptFileId),
            source.recordingId,
            source.receptionId,
        ) ?: error("Canonical transcript must be found")

        // then
        assertThat(saved.transcript).isEqualTo(original)
        assertThat(saved.storedAt).isEqualTo(STORED_AT)
        assertThat(saved.producedByAnalysisRequestId).isEqualTo(source.analysisId)
        assertThat(repository.findByProducedByAnalysisRequestId(source.analysisId)?.transcriptFileId).isEqualTo(saved.transcriptFileId)
        jdbc.queryForObject(
            "SELECT *, pg_typeof(transcript_text)::text AS text_type FROM $SCHEMA.reception_transcripts WHERE transcript_file_id = ?",
            { row, _ ->
                assertThat(row.getString("text_type")).isEqualTo("text")
                assertThat(row.getString("transcript_text")).isEqualTo(original)
                assertThat(row.getString("bucket")).isNull()
                assertThat(row.getString("object_key")).isNull()
                assertThat(row.getString("status")).isEqualTo("UPLOADED")
                assertThat(row.getLong("size_bytes")).isEqualTo(original.toByteArray(Charsets.UTF_8).size.toLong())
                assertThat(row.getBytes("checksum_sha256"))
                    .containsExactly(*MessageDigest.getInstance("SHA-256").digest(original.toByteArray(Charsets.UTF_8)))
                assertThat(row.getLong("persistence_version")).isZero()
                row.getObject("uploaded_at", OffsetDateTime::class.java).toInstant()
            },
            saved.transcriptFileId,
        ).also { assertThat(it).isEqualTo(STORED_AT) }
    }

    @Test
    fun `원문 ID가 맞아도 녹음 또는 접수가 다르면 조회하지 않는다`() {
        // given
        val source = source()
        val transcript = repository.saveAndFlush(transcript(source))
        entityManager.clear()

        // when & then
        assertThat(repository.findByTranscriptFileIdAndRecordingFileIdAndReceptionId(checkNotNull(transcript.transcriptFileId), id(), source.receptionId))
            .isNull()
        assertThat(repository.findByTranscriptFileIdAndRecordingFileIdAndReceptionId(checkNotNull(transcript.transcriptFileId), source.recordingId, id()))
            .isNull()
        assertThat(repository.findById(id())).isEmpty()
    }

    @ParameterizedTest
    @ValueSource(strings = ["same-id", "same-producer"])
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    fun `중복 원문 저장은 최초 문자열과 ID를 덮어쓰지 않는다`(duplicate: String) {
        // given
        val transaction = TransactionTemplate(transactionManager)
        val source = transaction.execute { source() }
        val first = transcript(source, "최초 STT 원문")
        transaction.executeWithoutResult { repository.saveAndFlush(first) }
        val second = ReceptionTranscript(source.receptionId, source.recordingId, source.analysisId, "바뀐 원문", STORED_AT.plusSeconds(60))

        // when
        val exception = assertThrows<DataIntegrityViolationException> {
            transaction.executeWithoutResult {
                if (duplicate == "same-id") {
                    jdbc.update(
                        "INSERT INTO $SCHEMA.reception_transcripts SELECT * FROM $SCHEMA.reception_transcripts WHERE transcript_file_id = ?",
                        first.transcriptFileId,
                    )
                } else {
                    repository.saveAndFlush(second)
                }
            }
        }
        val saved = repository.findByProducedByAnalysisRequestId(source.analysisId) ?: error("Original must remain")

        // then
        assertSqlState(exception, "23505")
        assertThat(saved.transcriptFileId).isEqualTo(first.transcriptFileId)
        assertThat(saved.transcript).isEqualTo("최초 STT 원문")
        assertThat(saved.storedAt).isEqualTo(STORED_AT)
    }

    @ParameterizedTest
    @ValueSource(strings = ["recording", "reception", "producer"])
    fun `생산 분석의 녹음과 접수 조합이 다른 원문은 DB에서 거부한다`(mismatch: String) {
        // given
        val source = source()
        val invalid = ReceptionTranscript(
            receptionId = if (mismatch == "reception") id() else source.receptionId,
            recordingFileId = if (mismatch == "recording") id() else source.recordingId,
            producedByAnalysisRequestId = if (mismatch == "producer") id() else source.analysisId,
            transcript = "테스트 원문",
            storedAt = STORED_AT,
        )

        // when
        val exception = assertThrows<DataIntegrityViolationException> { repository.saveAndFlush(invalid) }

        // then
        assertSqlState(exception, "23503")
    }

    @ParameterizedTest
    @ValueSource(strings = ["", " \t\r\n", "\u00A0", "\u2003\u3000", "too-long"])
    fun `DB도 빈 원문과 공백뿐인 원문 및 계약 한도 초과를 거부한다`(invalid: String) {
        // given
        val saved = repository.saveAndFlush(transcript(source()))
        val text = if (invalid == "too-long") "가".repeat(100_001) else invalid

        // when
        val exception = assertThrows<DataIntegrityViolationException> {
            jdbc.update(
                "UPDATE $SCHEMA.reception_transcripts SET transcript_text = ?, size_bytes = ? WHERE transcript_file_id = ?",
                text,
                text.toByteArray(Charsets.UTF_8).size,
                saved.transcriptFileId,
            )
        }

        // then
        assertSqlState(exception, "23514")
    }

    @ParameterizedTest
    @ValueSource(strings = ["s3-and-text", "pending", "wrong-size", "version"])
    fun `DB는 원문 저장 위치와 메타데이터의 불일치를 거부한다`(invalid: String) {
        // given
        val saved = repository.saveAndFlush(transcript(source()))
        val assignment = when (invalid) {
            "s3-and-text" -> "bucket = 'test-bucket', object_key = 'transcript.txt'"
            "pending" -> "status = 'PENDING'"
            "wrong-size" -> "size_bytes = size_bytes + 1"
            "version" -> "persistence_version = -1"
            else -> error("Unknown constraint case")
        }

        // when
        val exception = assertThrows<DataIntegrityViolationException> {
            jdbc.update("UPDATE $SCHEMA.reception_transcripts SET $assignment WHERE transcript_file_id = ?", saved.transcriptFileId)
        }

        // then
        assertSqlState(exception, "23514")
    }

    @ParameterizedTest
    @ValueSource(strings = ["transcript_text", "uploaded_at"])
    fun `DB는 원문과 보관 시각 누락을 거부한다`(column: String) {
        // given
        val saved = repository.saveAndFlush(transcript(source()))

        // when
        val exception = assertThrows<DataIntegrityViolationException> {
            jdbc.update("UPDATE $SCHEMA.reception_transcripts SET $column = NULL WHERE transcript_file_id = ?", saved.transcriptFileId)
        }

        // then
        assertSqlState(exception, "23502")
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    fun `V4는 STT가 없는 V3의 녹음과 분석 기록을 보존하며 적용된다`() {
        // given
        migrationAt("3").migrate()
        val source = source(UPGRADE_SCHEMA)

        // when
        val upgraded = migrationAt("4")
        upgraded.migrate()

        // then
        assertThat(upgraded.validateWithResult().validationSuccessful).isTrue()
        assertThat(upgraded.info().current()?.version?.version).isEqualTo("4")
        jdbc.queryForObject(
            """
            SELECT r.reception_id, r.recording_file_id, r.recording_received_at, a.reference_time
            FROM $UPGRADE_SCHEMA.reception_recordings r
            JOIN $UPGRADE_SCHEMA.reception_analysis_requests a ON a.recording_file_id = r.recording_file_id
            WHERE a.analysis_request_id = ?::text
            """.trimIndent(),
            { row, _ ->
                assertThat(row.getLong("reception_id")).isEqualTo(source.receptionId)
                assertThat(row.getLong("recording_file_id")).isEqualTo(source.recordingId)
                assertThat(row.getObject("reference_time", OffsetDateTime::class.java).toInstant()).isEqualTo(RECEIVED_AT)
                row.getObject("recording_received_at", OffsetDateTime::class.java).toInstant()
            },
            source.analysisId,
        ).also { assertThat(it).isEqualTo(RECEIVED_AT) }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM $UPGRADE_SCHEMA.reception_transcripts", Long::class.java)).isZero()
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    fun `예상과 달리 기존 S3 STT 행이 있으면 V4는 원본을 지우거나 빈 값으로 바꾸지 않고 실패한다`() {
        // given
        migrationAt("3", UNEXPECTED_SCHEMA).migrate()
        val source = source(UNEXPECTED_SCHEMA)
        val fileId = legacyTranscript(source, UNEXPECTED_SCHEMA)

        // when
        val exception = assertThrows<FlywayException> { migrationAt("4", UNEXPECTED_SCHEMA).migrate() }

        // then
        val sqlStates = generateSequence<Throwable>(exception) { it.cause }.filterIsInstance<SQLException>().map { it.sqlState }.toList()
        assertThat(sqlStates).contains("23502")
        assertThat(migrationAt("3", UNEXPECTED_SCHEMA).info().current()?.version?.version).isEqualTo("3")
        assertThat(
            jdbc.queryForObject(
                "SELECT object_key FROM $UNEXPECTED_SCHEMA.reception_transcripts WHERE transcript_file_id = ?::text",
                String::class.java,
                fileId,
            ),
        ).isEqualTo("legacy/$fileId.txt")
    }

    @AfterAll
    fun removeOwnedTestSchemas() {
        jdbc.execute("DROP SCHEMA IF EXISTS $UNEXPECTED_SCHEMA CASCADE")
        jdbc.execute("DROP SCHEMA IF EXISTS $UPGRADE_SCHEMA CASCADE")
        jdbc.execute("DROP SCHEMA IF EXISTS $SCHEMA CASCADE")
    }

    private fun transcript(source: Source, text: String = "테스트 STT 원문") = ReceptionTranscript(
        receptionId = source.receptionId,
        recordingFileId = source.recordingId,
        producedByAnalysisRequestId = source.analysisId,
        transcript = text,
        storedAt = STORED_AT,
    )

    private fun source(schema: String = SCHEMA): Source {
        val source = Source(id(), id(), id())
        jdbc.update(
            """
            INSERT INTO $schema.reception_recordings (
                recording_file_id, reception_id, uploaded_by_admin_id, original_filename,
                bucket, object_key, content_type, size_bytes, checksum_sha256,
                status, recording_received_at, created_at, updated_at
            ) VALUES (?, ?, '1', 'call.m4a', 'test-bucket', ?, 'audio/mp4', 1024, ?,
                'UPLOADED', ?, ?, ?)
            """.trimIndent(),
            source.recordingId,
            source.receptionId,
            "recordings/${source.recordingId}",
            ByteArray(32) { 1 },
            RECEIVED_AT.atOffset(ZoneOffset.UTC),
            RECEIVED_AT.minusSeconds(60).atOffset(ZoneOffset.UTC),
            RECEIVED_AT.atOffset(ZoneOffset.UTC),
        )
        jdbc.update(
            """
            INSERT INTO $schema.reception_analysis_requests (
                analysis_request_id, reception_id, recording_file_id, reception_version,
                requested_by_admin_id, mode, is_initial, reference_time, created_at, updated_at
            ) VALUES (?, ?, ?, 3, '1', 'FULL', TRUE, ?, ?, ?)
            """.trimIndent(),
            source.analysisId,
            source.receptionId,
            source.recordingId,
            RECEIVED_AT.atOffset(ZoneOffset.UTC),
            RECEIVED_AT.atOffset(ZoneOffset.UTC),
            RECEIVED_AT.atOffset(ZoneOffset.UTC),
        )
        return source
    }

    private fun legacyTranscript(source: Source, schema: String): Long {
        val fileId = id()
        jdbc.update(
            """
            INSERT INTO $schema.reception_transcripts (
                transcript_file_id, reception_id, recording_file_id, produced_by_analysis_request_id,
                bucket, object_key, size_bytes, checksum_sha256, status, uploaded_at, created_at, updated_at
            ) VALUES (?, ?, ?, ?, 'test-bucket', ?, 20, ?, 'UPLOADED', ?, ?, ?)
            """.trimIndent(),
            fileId,
            source.receptionId,
            source.recordingId,
            source.analysisId,
            "legacy/$fileId.txt",
            ByteArray(32) { 1 },
            STORED_AT.atOffset(ZoneOffset.UTC),
            STORED_AT.atOffset(ZoneOffset.UTC),
            STORED_AT.atOffset(ZoneOffset.UTC),
        )
        return fileId
    }

    private fun assertSqlState(exception: DataIntegrityViolationException, state: String) {
        assertThat(exception.mostSpecificCause).isInstanceOfSatisfying(SQLException::class.java) {
            assertThat(it.sqlState).isEqualTo(state)
        }
    }

    private fun migrationAt(version: String, schema: String = UPGRADE_SCHEMA) = Flyway.configure()
        .dataSource(dataSource)
        .schemas(schema)
        .defaultSchema(schema)
        .target(version)
        .load()

    private val nextId = java.util.concurrent.atomic.AtomicLong(1_000_000)

    private fun id() = nextId.incrementAndGet()

    private data class Source(val receptionId: Long, val recordingId: Long, val analysisId: Long)

    companion object {
        private val STORED_AT = Instant.parse("2026-11-16T00:00:00Z")
        private val RECEIVED_AT = STORED_AT.minusSeconds(60)
        private val SCHEMA = "transcript_test_" + UUID.randomUUID().toString().replace("-", "")
        private val UPGRADE_SCHEMA = SCHEMA + "_upgrade"
        private val UNEXPECTED_SCHEMA = SCHEMA + "_unexpected"

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
