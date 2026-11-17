package com.allcheck.reception.infrastructure.repository

import com.allcheck.global.config.HibernateConfig
import com.allcheck.reception.domain.entity.Reception
import com.allcheck.reception.domain.vo.analysis.ReceptionAiOriginal
import com.allcheck.reception.domain.vo.analysis.ReceptionAnalysisStatus
import com.allcheck.reception.domain.vo.review.ReceptionConfirmationData
import com.allcheck.reception.domain.vo.review.ReceptionData
import com.allcheck.reception.domain.vo.review.ReceptionReviewStatus
import com.allcheck.reception.domain.vo.review.WorkType
import jakarta.persistence.EntityManager
import org.assertj.core.api.Assertions.assertThat
import org.flywaydb.core.Flyway
import org.flywaydb.core.api.FlywayException
import org.junit.jupiter.api.AfterAll
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
import org.springframework.context.annotation.Import
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.dao.OptimisticLockingFailureException
import org.springframework.data.domain.PageRequest
import org.springframework.data.domain.Sort
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
class ReceptionRepositoryTest @Autowired constructor(
    private val repository: ReceptionRepository,
    private val entityManager: EntityManager,
    private val dataSource: DataSource,
    private val transactionManager: PlatformTransactionManager,
) {
    private val jdbc by lazy { JdbcTemplate(dataSource) }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    fun `동시 INSERT가 서로 다른 숫자 ID를 받고 롤백된 행과 번호는 재사용하지 않는다`() {
        // given
        val transaction = TransactionTemplate(transactionManager)
        val executor = Executors.newFixedThreadPool(2)
        val start = CountDownLatch(2)

        // when
        val ids = try {
            (1..2).map {
                executor.submit<Long> {
                    transaction.execute {
                        start.countDown()
                        check(start.await(5, TimeUnit.SECONDS))
                        val reception = newReception()
                        assertThat(reception.receptionId).isNull()
                        checkNotNull(repository.saveAndFlush(reception).receptionId)
                    }
                }
            }.map { it.get(10, TimeUnit.SECONDS) }
        } finally {
            executor.shutdownNow()
        }
        var rolledBackId: Long? = null
        assertThrows<IllegalStateException> {
            transaction.executeWithoutResult {
                rolledBackId = repository.saveAndFlush(newReception()).receptionId
                error("Simulated failure after identity generation.")
            }
        }
        val next = transaction.execute { repository.saveAndFlush(newReception()) }

        // then
        assertThat(ids).doesNotHaveDuplicates().allMatch { it > 0 }
        assertThat(repository.findAllById(ids)).hasSize(2)
        assertThat(repository.existsById(checkNotNull(rolledBackId))).isFalse()
        assertThat(next.receptionId).isNotEqualTo(rolledBackId)
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    fun `V7은 빈 V6 테이블의 참조와 기본키를 BIGINT IDENTITY로 함께 전환한다`() {
        // given
        migrationAt("6", IDENTITY_SCHEMA).migrate()

        // when
        val migration = migrationAt("7", IDENTITY_SCHEMA)
        migration.migrate()

        // then
        assertThat(migration.validateWithResult().validationSuccessful).isTrue()
        assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM information_schema.columns WHERE table_schema = ? AND is_identity = 'YES' AND data_type = 'bigint' AND identity_generation = 'BY DEFAULT'",
                Long::class.java,
                IDENTITY_SCHEMA,
            ),
        ).isEqualTo(8)
        assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM information_schema.columns WHERE table_schema = ? AND column_name LIKE '%_id' AND column_name NOT IN ('installation_id', 'trace_id', 'provider_message_id') AND data_type <> 'bigint'",
                Long::class.java,
                IDENTITY_SCHEMA,
            ),
        ).isZero()
        assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM pg_constraint c JOIN pg_namespace n ON n.oid = c.connamespace WHERE n.nspname = ? AND c.contype = 'f'",
                Long::class.java,
                IDENTITY_SCHEMA,
            ),
        ).isEqualTo(6)
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    fun `기존 문자열 ID가 있으면 V7은 임의 변환이나 삭제 없이 중단한다`() {
        // given
        migrationAt("6", EXISTING_ID_SCHEMA).migrate()
        jdbc.update(
            "INSERT INTO $EXISTING_ID_SCHEMA.receptions (reception_id, owner_admin_id, received_at, updated_at) VALUES ('rec_legacy', 'admin_legacy', ?, ?)",
            NOW.atOffset(ZoneOffset.UTC),
            NOW.atOffset(ZoneOffset.UTC),
        )

        // when
        assertThrows<FlywayException> { migrationAt("7", EXISTING_ID_SCHEMA).migrate() }

        // then
        assertThat(migrationAt("6", EXISTING_ID_SCHEMA).info().current()?.version?.version).isEqualTo("6")
        assertThat(jdbc.queryForObject("SELECT reception_id FROM $EXISTING_ID_SCHEMA.receptions", String::class.java)).isEqualTo("rec_legacy")
        assertThat(jdbc.queryForObject("SELECT owner_admin_id FROM $EXISTING_ID_SCHEMA.receptions", String::class.java)).isEqualTo("admin_legacy")
    }

    @Test
    fun `원본과 편집값이 없는 초안을 저장한 뒤 같은 의미로 조회한다`() {
        // given
        val startedAt = OffsetDateTime.parse("2026-11-15T13:30:00+09:00")
        val reception = Reception(id(), NOW.minusSeconds(3600), "02-1234-5678", startedAt)

        // when
        assertThat(reception.receptionId).isNull()
        repository.saveAndFlush(reception)
        entityManager.clear()
        val loaded = repository.findById(checkNotNull(reception.receptionId)).orElseThrow()

        // then
        assertThat(loaded.receptionId).isPositive()
        assertThat(loaded.ownerAdminId).isEqualTo(reception.ownerAdminId)
        assertThat(loaded.receivedAt).isEqualTo(reception.receivedAt)
        assertThat(loaded.callerPhone).isEqualTo(reception.callerPhone)
        assertThat(loaded.callStartedAt?.toInstant()).isEqualTo(startedAt.toInstant())
        assertThat(loaded.reviewStatus).isEqualTo(ReceptionReviewStatus.DRAFT)
        assertThat(loaded.version).isEqualTo(1)
        assertThat(loaded.finalData).isEqualTo(ReceptionData())
        assertThat(loaded.aiOriginal).isNull()
        assertThat(loaded.confirmedAt).isNull()
        assertThat(
            jdbc.queryForObject(
                "SELECT final_data = '{}'::jsonb FROM $SCHEMA.receptions WHERE reception_id = ?",
                Boolean::class.java,
                reception.receptionId,
            ),
        ).isTrue()
    }

    @Test
    fun `빈 AI 추출 객체는 원본이 없는 null과 구분해 보관한다`() {
        // given
        val reception = newReception()
        ReflectionTestUtils.setField(reception, "aiOriginal", ReceptionAiOriginal())

        // when
        repository.saveAndFlush(reception)
        entityManager.clear()
        val loaded = repository.findById(checkNotNull(reception.receptionId)).orElseThrow()

        // then
        assertThat(loaded.aiOriginal).isNotNull().isEqualTo(ReceptionAiOriginal())
        assertThat(
            jdbc.queryForObject(
                "SELECT ai_original = '{}'::jsonb FROM $SCHEMA.receptions WHERE reception_id = ?",
                Boolean::class.java,
                reception.receptionId,
            ),
        ).isTrue()
    }

    @ParameterizedTest
    @EnumSource(WorkType::class)
    fun `다섯 작업 유형의 원본과 최신값을 보존하며 확정과 해제를 저장한다`(workType: WorkType) {
        // given
        val reception = newReception()
        val original = ReceptionAiOriginal(
            workType = workType,
            symptoms = "AI 추출 증상",
            desiredVisitFrom = OffsetDateTime.parse("2026-11-15T14:00:00+09:00"),
            desiredVisitTo = OffsetDateTime.parse("2026-11-15T16:00:00+09:00"),
            notes = "AI 원본 메모",
        )
        ReflectionTestUtils.setField(reception, "aiOriginal", original)
        ReflectionTestUtils.setField(reception, "version", 4)
        ReflectionTestUtils.setField(reception, "recordingFileId", 1_007L)
        ReflectionTestUtils.setField(reception, "transcriptFileId", 1_004L)
        ReflectionTestUtils.setField(reception, "latestAnalysisRequestId", 1_005L)
        reception.confirm(reception.version, reviewedData().copy(workType = workType), ReceptionAnalysisStatus.SUCCEEDED, NOW)

        // when
        repository.saveAndFlush(reception)
        entityManager.clear()
        val confirmed = repository.findById(checkNotNull(reception.receptionId)).orElseThrow()

        // then
        assertThat(confirmed.reviewStatus).isEqualTo(ReceptionReviewStatus.CONFIRMED)
        assertThat(confirmed.version).isEqualTo(5)
        assertThat(confirmed.confirmedAt).isEqualTo(NOW)
        assertThat(confirmed.finalData.desiredVisitFrom).isEqualTo(reviewedData().desiredVisitFrom)
        assertThat(confirmed.finalData.desiredVisitTo).isEqualTo(reviewedData().desiredVisitTo)
        assertThat(confirmed.finalData.contactPhone).isEqualTo(reviewedData().contactPhone)
        assertThat(confirmed.finalData.notes).isEqualTo(reviewedData().notes)
        assertThat(confirmed.finalData.workType).isEqualTo(workType)
        assertThat(confirmed.aiOriginal).isEqualTo(original)

        // when
        confirmed.reopen(confirmed.version, NOW.plusSeconds(60))
        repository.flush()
        entityManager.clear()
        val reopened = repository.findById(checkNotNull(reception.receptionId)).orElseThrow()

        // then
        assertThat(reopened.reviewStatus).isEqualTo(ReceptionReviewStatus.DRAFT)
        assertThat(reopened.version).isEqualTo(6)
        assertThat(reopened.confirmedAt).isNull()
        assertThat(reopened.updatedAt).isEqualTo(NOW.plusSeconds(60))
        assertThat(reopened.finalData).isEqualTo(confirmed.finalData)
        assertThat(reopened.aiOriginal).isEqualTo(original)
        assertThat(reopened.recordingFileId).isEqualTo(1_007L)
        assertThat(reopened.transcriptFileId).isEqualTo(1_004L)
        assertThat(reopened.latestAnalysisRequestId).isEqualTo(1_005L)
    }

    @Test
    fun `소유자 조회는 다른 관리자 접수를 제외하고 페이지 합계도 해당 소유자만 센다`() {
        // given
        val owner = id()
        val first = Reception(owner, NOW.minusSeconds(60))
        val second = Reception(owner, NOW)
        val other = newReception()
        repository.saveAllAndFlush(listOf(first, second, other))
        entityManager.clear()

        // when
        val page = repository.findAllByOwnerAdminId(
            owner,
            PageRequest.of(0, 1, Sort.by(Sort.Direction.DESC, "receivedAt")),
        )

        // then
        assertThat(page.totalElements).isEqualTo(2)
        assertThat(page.content.map { it.receptionId }).containsExactly(second.receptionId)
        assertThat(repository.findByReceptionIdAndOwnerAdminId(checkNotNull(first.receptionId), owner)).isNotNull()
        assertThat(repository.findByReceptionIdAndOwnerAdminId(checkNotNull(other.receptionId), owner)).isNull()
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    fun `독립 트랜잭션의 동시 검토 완료는 한 번만 저장하고 패자의 변경을 남기지 않는다`() {
        // given
        val reception = newReception()
        val transaction = TransactionTemplate(transactionManager)
        transaction.executeWithoutResult { repository.saveAndFlush(reception) }
        val loaded = CountDownLatch(2)
        val executor = Executors.newFixedThreadPool(2)

        try {
            // when
            val futures = listOf("editor-1", "editor-2").map { editor ->
                editor to executor.submit<Boolean> {
                    try {
                        transaction.executeWithoutResult {
                            val current = repository.findById(checkNotNull(reception.receptionId)).orElseThrow()
                            loaded.countDown()
                            check(loaded.await(10, TimeUnit.SECONDS)) { "Both transactions must read before writing." }
                            current.confirm(1, reviewedData().copy(notes = editor), analysisStatus = null, now = NOW)
                            repository.flush()
                        }
                        true
                    } catch (_: OptimisticLockingFailureException) {
                        false
                    }
                }
            }
            val results = futures.map { (editor, future) -> editor to future.get(15, TimeUnit.SECONDS) }
            val winner = results.single { it.second }.first
            val saved = repository.findById(checkNotNull(reception.receptionId)).orElseThrow()

            // then
            assertThat(results.count { it.second }).isEqualTo(1)
            assertThat(saved.finalData.notes).isEqualTo(winner)
            assertThat(saved.reviewStatus).isEqualTo(ReceptionReviewStatus.CONFIRMED)
            assertThat(saved.confirmedAt).isEqualTo(NOW)
            assertThat(saved.version).isEqualTo(2)
        } finally {
            executor.shutdownNow()
        }
    }

    @ParameterizedTest
    @ValueSource(strings = ["missing-data", "invalid-version", "draft-with-request", "unknown-work-type"])
    fun `DB는 불완전한 확정과 잘못된 버전 및 미확정 의뢰 연결을 거부한다`(invalidState: String) {
        // given
        val reception = repository.saveAndFlush(newReception())
        val assignment = when (invalidState) {
            "missing-data" -> "review_status = 'CONFIRMED', confirmed_at = ?, updated_at = ?"
            "invalid-version" -> "version = 0"
            "draft-with-request" -> "request_id = 101"
            "unknown-work-type" -> "final_data = jsonb_set(final_data, '{workType}', '\"UNKNOWN\"'::jsonb)"
            else -> error("Unknown constraint case")
        }
        val arguments = if (invalidState == "missing-data") {
            arrayOf<Any?>(NOW.atOffset(ZoneOffset.UTC), NOW.atOffset(ZoneOffset.UTC), reception.receptionId)
        } else {
            arrayOf(reception.receptionId)
        }
        if (invalidState == "unknown-work-type") {
            reception.confirm(1, reviewedData(), analysisStatus = null, now = NOW)
            repository.flush()
        }

        // when
        val exception = assertThrows<DataIntegrityViolationException> {
            jdbc.update("UPDATE $SCHEMA.receptions SET $assignment WHERE reception_id = ?", *arguments)
        }

        // then
        assertThat(exception.mostSpecificCause).isInstanceOfSatisfying(SQLException::class.java) {
            assertThat(it.sqlState).isEqualTo("23514")
        }
    }

    @Test
    fun `동일한 의뢰를 여러 접수에 연결할 수 없다`() {
        // given
        val first = newReception().also { it.confirm(1, reviewedData(), analysisStatus = null, now = NOW) }
        val second = newReception().also { it.confirm(1, reviewedData(), analysisStatus = null, now = NOW) }
        repository.saveAllAndFlush(listOf(first, second))
        jdbc.update("UPDATE $SCHEMA.receptions SET request_id = 102 WHERE reception_id = ?", first.receptionId)

        // when
        val exception = assertThrows<DataIntegrityViolationException> {
            jdbc.update("UPDATE $SCHEMA.receptions SET request_id = 102 WHERE reception_id = ?", second.receptionId)
        }

        // then
        assertThat(exception.mostSpecificCause).isInstanceOfSatisfying(SQLException::class.java) {
            assertThat(it.sqlState).isEqualTo("23505")
        }
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    fun `V1에 보관한 녹음 메타데이터를 보존하며 V2로 확장한다`() {
        // given
        migrationAt("1").migrate()
        jdbc.update(
            """
            INSERT INTO $UPGRADE_SCHEMA.reception_recordings (
                recording_file_id, reception_id, uploaded_by_admin_id, original_filename,
                bucket, object_key, content_type, size_bytes, checksum_sha256
            ) VALUES ('legacy-recording', 'legacy-reception', 'legacy-admin', 'legacy.m4a',
                'test-bucket', 'legacy.m4a', 'audio/mp4', 1, decode(repeat('00', 32), 'hex'))
            """.trimIndent(),
        )

        // when
        val upgraded = migrationAt("2")
        upgraded.migrate()

        // then
        assertThat(upgraded.validateWithResult().validationSuccessful).isTrue()
        assertThat(upgraded.info().current()?.version?.version).isEqualTo("2")
        assertThat(jdbc.queryForObject("SELECT original_filename FROM $UPGRADE_SCHEMA.reception_recordings", String::class.java))
            .isEqualTo("legacy.m4a")
        assertThat(jdbc.queryForObject("SELECT count(*) FROM $UPGRADE_SCHEMA.receptions", Long::class.java)).isZero()
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    fun `V5는 기존 확정값을 보존하고 보일러와 방수 유형의 저장을 허용한다`() {
        // given
        migrationAt("4", WORK_TYPE_UPGRADE_SCHEMA).migrate()
        val originalData = """
            {"workType":"BLOCKAGE","symptoms":"배수 불가","address":"서울시 강남구 테스트 주소",
             "desiredVisitFrom":"2026-11-15T14:00:00+09:00","desiredVisitTo":"2026-11-15T16:00:00+09:00",
             "contactPhone":"010-1234-5678","notes":"기존 메모"}
        """.trimIndent()
        jdbc.update(
            """
            INSERT INTO $WORK_TYPE_UPGRADE_SCHEMA.receptions (
                reception_id, owner_admin_id, received_at, review_status, version, final_data, confirmed_at, updated_at
            ) VALUES ('legacy-type', '1', ?, 'CONFIRMED', 2, ?::jsonb, ?, ?)
            """.trimIndent(),
            NOW.minusSeconds(60).atOffset(ZoneOffset.UTC),
            originalData,
            NOW.atOffset(ZoneOffset.UTC),
            NOW.atOffset(ZoneOffset.UTC),
        )
        assertThrows<DataIntegrityViolationException> {
            jdbc.update(
                "UPDATE $WORK_TYPE_UPGRADE_SCHEMA.receptions SET final_data = jsonb_set(final_data, '{workType}', '\"BOILER\"'::jsonb)",
            )
        }

        // when
        val upgraded = migrationAt("5", WORK_TYPE_UPGRADE_SCHEMA)
        upgraded.migrate()

        // then
        assertThat(upgraded.validateWithResult().validationSuccessful).isTrue()
        assertThat(upgraded.info().current()?.version?.version).isEqualTo("5")
        assertThat(
            jdbc.queryForObject(
                "SELECT final_data = ?::jsonb AND version = 2 FROM $WORK_TYPE_UPGRADE_SCHEMA.receptions WHERE reception_id = 'legacy-type'",
                Boolean::class.java,
                originalData,
            ),
        ).isTrue()
        for (workType in listOf("BOILER", "WATERPROOFING")) {
            jdbc.update(
                "UPDATE $WORK_TYPE_UPGRADE_SCHEMA.receptions SET final_data = jsonb_set(final_data, '{workType}', to_jsonb(?::text)) WHERE reception_id = 'legacy-type'",
                workType,
            )
            assertThat(
                jdbc.queryForObject(
                    "SELECT final_data ->> 'workType' FROM $WORK_TYPE_UPGRADE_SCHEMA.receptions WHERE reception_id = 'legacy-type'",
                    String::class.java,
                ),
            ).isEqualTo(workType)
        }
    }

    @AfterAll
    fun removeOwnedTestSchemas() {
        // 이름 모두 이 테스트 실행에서 생성한 UUID이며 외부 설정으로 바꾸지 않는다.
        jdbc.execute("DROP SCHEMA IF EXISTS $IDENTITY_SCHEMA CASCADE")
        jdbc.execute("DROP SCHEMA IF EXISTS $EXISTING_ID_SCHEMA CASCADE")
        jdbc.execute("DROP SCHEMA IF EXISTS $UPGRADE_SCHEMA CASCADE")
        jdbc.execute("DROP SCHEMA IF EXISTS $WORK_TYPE_UPGRADE_SCHEMA CASCADE")
        jdbc.execute("DROP SCHEMA IF EXISTS $SCHEMA CASCADE")
    }

    private fun migrationAt(version: String, schema: String = UPGRADE_SCHEMA) = Flyway.configure()
        .dataSource(dataSource)
        .schemas(schema)
        .defaultSchema(schema)
        .target(version)
        .load()

    private fun newReception() = Reception(id(), NOW.minusSeconds(7200))

    private val nextId = java.util.concurrent.atomic.AtomicLong(1_000_000)

    private fun id() = nextId.incrementAndGet()

    private fun reviewedData() = ReceptionConfirmationData(
        workType = WorkType.BLOCKAGE,
        symptoms = "싱크대 배수 불가",
        address = "서울시 강남구 예시로 10",
        desiredVisitFrom = OffsetDateTime.parse("2026-11-15T14:00:00+09:00"),
        desiredVisitTo = OffsetDateTime.parse("2026-11-15T16:00:00+09:00"),
        contactPhone = "010-1234-5678",
        notes = "방문 전 연락",
    )

    companion object {
        private val NOW = Instant.parse("2026-11-15T06:00:00Z")
        private val SCHEMA = "reception_test_" + UUID.randomUUID().toString().replace("-", "")
        private val UPGRADE_SCHEMA = SCHEMA + "_upgrade"
        private val WORK_TYPE_UPGRADE_SCHEMA = SCHEMA + "_work_type"
        private val IDENTITY_SCHEMA = SCHEMA + "_identity"
        private val EXISTING_ID_SCHEMA = SCHEMA + "_existing_id"

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
