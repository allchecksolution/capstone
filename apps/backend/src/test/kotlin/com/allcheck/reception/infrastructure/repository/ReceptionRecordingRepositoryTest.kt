package com.allcheck.reception.infrastructure.repository

import com.allcheck.global.config.HibernateConfig
import com.allcheck.reception.domain.entity.ReceptionRecording
import com.allcheck.reception.domain.vo.RecordingUploadStatus
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
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.annotation.DirtiesContext
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
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
class ReceptionRecordingRepositoryTest @Autowired constructor(
    private val repository: ReceptionRecordingRepository,
    private val entityManager: EntityManager,
    private val dataSource: DataSource,
    private val transactionManager: PlatformTransactionManager,
) {
    private val jdbc by lazy { JdbcTemplate(dataSource) }

    @Test
    fun `예약 메타데이터를 보관하고 접수와 업로더가 일치할 때만 조회한다`() {
        // given
        val recording = recording()
        repository.saveAndFlush(recording)
        entityManager.clear()

        // when
        val loaded = repository.findByRecordingFileIdAndReceptionIdAndUploadedByAdminId(
            recording.recordingFileId,
            recording.receptionId,
            recording.uploadedByAdminId,
        ) ?: error("Saved recording must be found")

        // then
        assertThat(loaded.originalFilename).isEqualTo("call.m4a")
        assertThat(loaded.bucket).isEqualTo("test-bucket")
        assertThat(loaded.objectKey).isEqualTo(recording.objectKey)
        assertThat(loaded.contentType).isEqualTo("audio/mp4")
        assertThat(loaded.sizeBytes).isEqualTo(1024)
        assertThat(loaded.checksumSha256).containsExactly(*digest())
        assertThat(loaded.status).isEqualTo(RecordingUploadStatus.PENDING)
        assertThat(loaded.createdAt).isEqualTo(CREATED_AT)
        assertThat(loaded.updatedAt).isEqualTo(CREATED_AT)
        assertThat(loaded.recordingReceivedAt).isNull()
        assertThat(repository.findByReceptionId(recording.receptionId)?.recordingFileId).isEqualTo(recording.recordingFileId)
        assertThat(repository.findByRecordingFileIdAndReceptionIdAndUploadedByAdminId(recording.recordingFileId, id(), recording.uploadedByAdminId))
            .isNull()
        assertThat(repository.findByRecordingFileIdAndReceptionIdAndUploadedByAdminId(recording.recordingFileId, recording.receptionId, id()))
            .isNull()
    }

    @Test
    fun `미완료 파일 재시도 확인은 새 행이나 DB 수정 버전을 만들지 않는다`() {
        // given
        val recording = repository.saveAndFlush(recording())

        // when
        recording.ensureUploadCanBeRetried()
        recording.ensureUploadCanBeRetried()
        repository.flush()
        entityManager.clear()
        val loaded = repository.findByReceptionId(recording.receptionId) ?: error("Reservation must remain")

        // then
        assertThat(loaded.recordingFileId).isEqualTo(recording.recordingFileId)
        assertThat(loaded.objectKey).isEqualTo(recording.objectKey)
        assertThat(loaded.checksumSha256).containsExactly(*digest())
        assertThat(loaded.updatedAt).isEqualTo(CREATED_AT)
        assertThat(persistenceVersion(loaded.recordingFileId)).isEqualTo(0)
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `대기와 완료 상태 모두 같은 접수의 두 번째 녹음을 거부한다`(uploaded: Boolean) {
        // given
        val first = recording()
        if (uploaded) first.completeUpload("audio/mp4", 1024, digest(), RECEIVED_AT)
        repository.saveAndFlush(first)
        val replacement = recording(receptionId = first.receptionId)

        // when
        val exception = assertThrows<DataIntegrityViolationException> { repository.saveAndFlush(replacement) }

        // then
        assertSqlState(exception, "23505")
    }

    @Test
    fun `같은 S3 객체를 다른 접수의 파일로 중복 예약할 수 없다`() {
        // given
        val first = repository.saveAndFlush(recording())
        val collision = recording(objectKey = first.objectKey)

        // when
        val exception = assertThrows<DataIntegrityViolationException> { repository.saveAndFlush(collision) }

        // then
        assertSqlState(exception, "23505")
    }

    @Test
    fun `중복 완료와 체크섬 조회는 최초 원본 및 완료 시각을 수정하지 않는다`() {
        // given
        val recording = repository.saveAndFlush(recording())
        recording.completeUpload("audio/mp4", 1024, digest(), RECEIVED_AT)
        repository.flush()
        entityManager.clear()
        val completed = repository.findById(recording.recordingFileId).orElseThrow()
        val originalVersion = persistenceVersion(completed.recordingFileId)

        // when
        completed.checksumSha256[0] = 99
        val changed = completed.completeUpload("audio/mpeg", 2048, byteArrayOf(1), RECEIVED_AT.plusSeconds(60))
        repository.flush()
        entityManager.clear()
        val loaded = repository.findById(recording.recordingFileId).orElseThrow()

        // then
        assertThat(changed).isFalse()
        assertThat(loaded.status).isEqualTo(RecordingUploadStatus.UPLOADED)
        assertThat(loaded.recordingReceivedAt).isEqualTo(RECEIVED_AT)
        assertThat(loaded.updatedAt).isEqualTo(RECEIVED_AT)
        assertThat(loaded.sizeBytes).isEqualTo(1024)
        assertThat(loaded.contentType).isEqualTo("audio/mp4")
        assertThat(loaded.checksumSha256).containsExactly(*digest())
        assertThat(persistenceVersion(loaded.recordingFileId)).isEqualTo(originalVersion)
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    fun `동시 완료 통보는 하나의 트랜잭션만 최초 수신 시각을 저장한다`() {
        // given
        val recording = recording()
        val transaction = TransactionTemplate(transactionManager)
        transaction.executeWithoutResult { repository.saveAndFlush(recording) }
        val loaded = CountDownLatch(2)
        val executor = Executors.newFixedThreadPool(2)

        try {
            // when
            val futures = listOf(RECEIVED_AT, RECEIVED_AT.plusSeconds(60)).map { receivedAt ->
                receivedAt to executor.submit<Boolean> {
                    try {
                        transaction.executeWithoutResult {
                            val current = repository.findById(recording.recordingFileId).orElseThrow()
                            loaded.countDown()
                            check(loaded.await(10, TimeUnit.SECONDS)) { "Both transactions must read before completion." }
                            current.completeUpload("audio/mp4", 1024, digest(), receivedAt)
                            repository.flush()
                        }
                        true
                    } catch (_: OptimisticLockingFailureException) {
                        false
                    }
                }
            }
            val results = futures.map { (time, future) -> time to future.get(15, TimeUnit.SECONDS) }
            val winner = results.single { it.second }.first
            val saved = repository.findById(recording.recordingFileId).orElseThrow()

            // then
            assertThat(results.count { it.second }).isEqualTo(1)
            assertThat(saved.status).isEqualTo(RecordingUploadStatus.UPLOADED)
            assertThat(saved.recordingReceivedAt).isEqualTo(winner)
            assertThat(saved.updatedAt).isEqualTo(winner)
            assertThat(saved.checksumSha256).containsExactly(*digest())
            assertThat(persistenceVersion(saved.recordingFileId)).isEqualTo(1)
        } finally {
            executor.shutdownNow()
        }
    }

    @ParameterizedTest
    @ValueSource(strings = ["checksum", "state", "version"])
    fun `DB 제약은 잘못된 체크섬과 완료 상태 및 수정 버전을 거부한다`(invalid: String) {
        // given
        val recording = repository.saveAndFlush(recording())
        val assignment = when (invalid) {
            "checksum" -> "checksum_sha256 = decode('00', 'hex')"
            "state" -> "status = 'UPLOADED'"
            "version" -> "persistence_version = -1"
            else -> error("Unknown constraint case")
        }

        // when
        val exception = assertThrows<DataIntegrityViolationException> {
            jdbc.update("UPDATE $SCHEMA.reception_recordings SET $assignment WHERE recording_file_id = ?", recording.recordingFileId)
        }

        // then
        assertSqlState(exception, "23514")
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    fun `V3 적용 시 기존 완료 녹음의 메타데이터와 최초 수신 시각을 보존한다`() {
        // given
        migrationAt("2").migrate()
        jdbc.update(
            """
            INSERT INTO $UPGRADE_SCHEMA.reception_recordings (
                recording_file_id, reception_id, uploaded_by_admin_id, original_filename,
                bucket, object_key, content_type, size_bytes, checksum_sha256,
                status, recording_received_at, created_at, updated_at
            ) VALUES ('legacy-recording', 'legacy-reception', 'legacy-admin', 'legacy.m4a',
                'test-bucket', 'legacy.m4a', 'audio/mp4', 1024, ?, 'UPLOADED', ?, ?, ?)
            """.trimIndent(),
            digest(),
            RECEIVED_AT.atOffset(ZoneOffset.UTC),
            CREATED_AT.atOffset(ZoneOffset.UTC),
            RECEIVED_AT.atOffset(ZoneOffset.UTC),
        )

        // when
        val upgraded = migrationAt("3")
        upgraded.migrate()

        // then
        assertThat(upgraded.validateWithResult().validationSuccessful).isTrue()
        assertThat(upgraded.info().current()?.version?.version).isEqualTo("3")
        val version = jdbc.queryForObject("SELECT * FROM $UPGRADE_SCHEMA.reception_recordings") { row, _ ->
            assertThat(row.getString("original_filename")).isEqualTo("legacy.m4a")
            assertThat(row.getString("object_key")).isEqualTo("legacy.m4a")
            assertThat(row.getBytes("checksum_sha256")).containsExactly(*digest())
            assertThat(row.getString("status")).isEqualTo("UPLOADED")
            assertThat(row.getObject("recording_received_at", OffsetDateTime::class.java).toInstant()).isEqualTo(RECEIVED_AT)
            row.getLong("persistence_version")
        }
        assertThat(version).isZero()
    }

    @AfterAll
    fun removeOwnedTestSchemas() {
        jdbc.execute("DROP SCHEMA IF EXISTS $UPGRADE_SCHEMA CASCADE")
        jdbc.execute("DROP SCHEMA IF EXISTS $SCHEMA CASCADE")
    }

    private fun assertSqlState(exception: DataIntegrityViolationException, state: String) {
        assertThat(exception.mostSpecificCause).isInstanceOfSatisfying(SQLException::class.java) {
            assertThat(it.sqlState).isEqualTo(state)
        }
    }

    private fun persistenceVersion(recordingFileId: String) = jdbc.queryForObject(
        "SELECT persistence_version FROM $SCHEMA.reception_recordings WHERE recording_file_id = ?",
        Long::class.java,
        recordingFileId,
    )

    private fun migrationAt(version: String) = Flyway.configure()
        .dataSource(dataSource)
        .schemas(UPGRADE_SCHEMA)
        .defaultSchema(UPGRADE_SCHEMA)
        .target(version)
        .load()

    private fun recording(
        receptionId: String = id(),
        objectKey: String = "recordings/${id()}.m4a",
    ) = ReceptionRecording(
        recordingFileId = id(),
        receptionId = receptionId,
        uploadedByAdminId = id(),
        originalFilename = "call.m4a",
        bucket = "test-bucket",
        objectKey = objectKey,
        contentType = "audio/mp4",
        sizeBytes = 1024,
        checksum = digest(),
        createdAt = CREATED_AT,
    )

    private fun id() = UUID.randomUUID().toString()

    private fun digest() = ByteArray(32) { it.toByte() }

    companion object {
        private val CREATED_AT = Instant.parse("2026-11-16T00:00:00Z")
        private val RECEIVED_AT = CREATED_AT.plusSeconds(60)
        private val SCHEMA = "recording_test_" + UUID.randomUUID().toString().replace("-", "")
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
