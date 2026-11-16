package com.allcheck.reception.application

import com.allcheck.global.config.HibernateConfig
import com.allcheck.global.exception.BusinessException
import com.allcheck.global.exception.GlobalErrorCode
import com.allcheck.reception.domain.vo.ReceptionConfirmationData
import com.allcheck.reception.domain.vo.ReceptionCreationData
import com.allcheck.reception.domain.vo.ReceptionExternalId
import com.allcheck.reception.domain.vo.WorkType
import com.allcheck.reception.exception.ReceptionErrorCode
import com.allcheck.reception.infrastructure.repository.ReceptionCommandReceiptRepository
import com.allcheck.reception.infrastructure.repository.ReceptionRepository
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.assertThrows
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.autoconfigure.ImportAutoConfiguration
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest
import org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.annotation.DirtiesContext
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
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
@Import(HibernateConfig::class, ReceptionService::class, ReceptionQueryService::class, ReceptionCommandService::class, ReceptionCommandServiceTest.TimeConfig::class)
@ActiveProfiles("test")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class ReceptionCommandServiceTest @Autowired constructor(
    private val commands: ReceptionCommandService,
    private val receptions: ReceptionRepository,
    private val receipts: ReceptionCommandReceiptRepository,
    private val clock: PausingClock,
    private val transactionManager: PlatformTransactionManager,
    private val dataSource: DataSource,
) {
    @BeforeEach
    fun resetClock() {
        clock.pause = null
    }

    @Test
    fun `같은 생성 요청은 하나의 접수만 만들고 최초 상세 응답을 복원한다`() {
        // given
        val key = key()
        val first = commands.create(1, key, ReceptionCreationData("010-1234-5678"))

        // when
        val replay = commands.create(1, key, ReceptionCreationData("010-1234-5678"))

        // then
        assertThat(first.replayed).isFalse()
        assertThat(replay.replayed).isTrue()
        assertThat(replay.value).isEqualTo(first.value)
        assertThat(receipts.findByActorAdminIdAndOperationAndScopeReceptionIdAndIdempotencyKey(1, "CREATE", 0, key)).isNotNull()
    }

    @Test
    fun `같은 키의 다른 본문은 거부하고 다른 관리자에게는 키 범위를 분리한다`() {
        // given
        val key = key()
        val first = commands.create(1, key, ReceptionCreationData())

        // when
        val exception = assertThrows<BusinessException> { commands.create(1, key, ReceptionCreationData("010-1234-5678")) }
        val other = commands.create(2, key, ReceptionCreationData())

        // then
        assertThat(exception.errorCode).isSameAs(GlobalErrorCode.IDEMPOTENCY_KEY_REUSED)
        assertThat(other.replayed).isFalse()
        assertThat(other.value.receptionId).isNotEqualTo(first.value.receptionId)
    }

    @Test
    fun `동시에 같은 키가 처리 중이면 기다리며 중복 실행하지 않고 충돌을 반환한다`() {
        // given
        val key = key()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        clock.pause = entered to release
        val executor = Executors.newSingleThreadExecutor()

        try {
            // when
            val first = executor.submit<ReceptionCommandResult<com.allcheck.reception.presentation.dto.response.ReceptionDetailResponse>> {
                commands.create(1, key, ReceptionCreationData())
            }
            check(entered.await(5, TimeUnit.SECONDS))
            val exception = assertThrows<BusinessException> { commands.create(1, key, ReceptionCreationData()) }
            release.countDown()
            val succeeded = first.get(10, TimeUnit.SECONDS)
            clock.pause = null
            val replay = commands.create(1, key, ReceptionCreationData())

            // then
            assertThat(exception.errorCode).isSameAs(GlobalErrorCode.OPERATION_IN_PROGRESS)
            assertThat(replay.value).isEqualTo(succeeded.value)
            assertThat(replay.replayed).isTrue()
        } finally {
            release.countDown()
            executor.shutdownNow()
        }
    }

    @Test
    fun `상위 트랜잭션이 실패하면 접수와 성공 응답 기록을 함께 롤백한다`() {
        // given
        val key = key()
        var abortedId: Long? = null

        // when
        assertThrows<IllegalStateException> {
            TransactionTemplate(transactionManager).executeWithoutResult {
                val value = commands.create(1, key, ReceptionCreationData()).value
                abortedId = ReceptionExternalId.RECEPTION.parse(value.receptionId)
                error("Simulated rollback after receipt insertion.")
            }
        }

        // then
        assertThat(receptions.existsById(checkNotNull(abortedId))).isFalse()
        assertThat(receipts.findByActorAdminIdAndOperationAndScopeReceptionIdAndIdempotencyKey(1, "CREATE", 0, key)).isNull()
        assertThat(commands.create(1, key, ReceptionCreationData()).replayed).isFalse()
    }

    @Test
    fun `이후 수정이 있어도 검토 완료 재시도는 최초 값과 시간대 버전을 재현한다`() {
        // given
        val created = commands.create(1, key(), ReceptionCreationData()).value
        val id = ReceptionExternalId.RECEPTION.parse(created.receptionId)
        val confirmKey = key()
        val data = reviewedData()
        val confirmed = commands.confirm(1, id, confirmKey, 1, data)
        commands.reopen(1, id, key(), 2)
        commands.confirm(1, id, key(), 3, data.copy(notes = "최신 메모"))

        // when
        val replay = commands.confirm(1, id, confirmKey, 1, data)

        // then
        assertThat(replay.replayed).isTrue()
        assertThat(replay.value).isEqualTo(confirmed.value)
        assertThat(replay.value.finalData.desiredVisitFrom.offset).isEqualTo(ZoneOffset.ofHours(9))
        assertThat(receptions.findById(id).orElseThrow().version).isEqualTo(4)
        assertThat(receptions.findById(id).orElseThrow().finalData.notes).isEqualTo("최신 메모")
        val reused = assertThrows<BusinessException> { commands.confirm(1, id, confirmKey, 1, data.copy(notes = "다른 본문")) }
        assertThat(reused.errorCode).isSameAs(GlobalErrorCode.IDEMPOTENCY_KEY_REUSED)
    }

    @Test
    fun `실패한 검토 입력은 키를 소모하지 않으며 보완한 요청을 같은 키로 처리한다`() {
        // given
        val id = ReceptionExternalId.RECEPTION.parse(commands.create(1, key(), ReceptionCreationData()).value.receptionId)
        val key = key()

        // when
        assertThrows<BusinessException> { commands.confirm(1, id, key, 1, reviewedData().copy(contactPhone = "")) }
        val fixed = commands.confirm(1, id, key, 1, reviewedData())

        // then
        assertThat(fixed.replayed).isFalse()
        assertThat(fixed.value.version).isEqualTo(2)
    }

    @Test
    fun `성공 응답 재현도 현재 접수 접근 권한을 다시 검사한다`() {
        // given
        val key = key()
        val first = commands.create(1, key, ReceptionCreationData()).value
        val id = ReceptionExternalId.RECEPTION.parse(first.receptionId)
        // 정상 MVP에 소유권 변경 UI는 없으며 서버 측 접근 회수 상태에 대한 방어를 검증한다.
        JdbcTemplate(dataSource).update("UPDATE $SCHEMA.receptions SET owner_admin_id = 2 WHERE reception_id = ?", id)

        // when
        val exception = assertThrows<BusinessException> { commands.create(1, key, ReceptionCreationData()) }

        // then
        assertThat(exception.errorCode).isSameAs(ReceptionErrorCode.RECEPTION_NOT_FOUND)
    }

    @AfterAll
    fun removeOwnedSchema() {
        JdbcTemplate(dataSource).execute("DROP SCHEMA IF EXISTS $SCHEMA CASCADE")
    }

    private fun key() = UUID.randomUUID().toString()

    private fun reviewedData() = ReceptionConfirmationData(
        WorkType.BOILER,
        "보일러 누수",
        "서울시 강남구 예시로 10",
        OffsetDateTime.parse("2026-11-17T14:00:00+09:00"),
        OffsetDateTime.parse("2026-11-17T16:00:00+09:00"),
        "010-1234-5678",
        "방문 전 연락",
    )

    @TestConfiguration(proxyBeanMethods = false)
    class TimeConfig {
        @Bean
        fun clock() = PausingClock()
    }

    class PausingClock : Clock() {
        @Volatile
        var pause: Pair<CountDownLatch, CountDownLatch>? = null

        override fun instant(): Instant {
            pause?.let { (entered, release) ->
                entered.countDown()
                check(release.await(5, TimeUnit.SECONDS))
            }
            return NOW
        }
        override fun getZone(): ZoneId = ZoneOffset.UTC
        override fun withZone(zone: ZoneId): Clock = fixed(NOW, zone)
    }

    companion object {
        private val NOW = Instant.parse("2026-11-17T06:00:00Z")
        private val SCHEMA = "receipt_test_" + UUID.randomUUID().toString().replace("-", "")

        @JvmStatic
        @DynamicPropertySource
        fun database(registry: DynamicPropertyRegistry) {
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
