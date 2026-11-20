package com.allcheck.identity.infrastructure

import com.allcheck.identity.AdminRole
import com.allcheck.identity.UserType
import com.allcheck.identity.domain.entity.account.Account
import com.allcheck.identity.domain.entity.session.AuthSession
import com.allcheck.identity.domain.vo.account.AdminCredential
import com.allcheck.identity.domain.vo.account.SocialLogin
import com.allcheck.identity.domain.vo.session.RefreshTokenHash
import com.allcheck.identity.infrastructure.repository.account.AccountRepository
import com.allcheck.identity.infrastructure.repository.session.AuthSessionRepository
import jakarta.persistence.EntityManager
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.dao.OptimisticLockingFailureException
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.support.TransactionTemplate
import java.sql.Timestamp
import java.time.Duration
import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@DataJpaTest(
    properties = [
        "spring.datasource.url=\${IDENTITY_TEST_DATABASE_URL:jdbc:postgresql://localhost:15432/allcheck_identity_test}",
        "spring.datasource.username=\${IDENTITY_TEST_DATABASE_USER:allcheck}",
        "spring.datasource.password=\${IDENTITY_TEST_DATABASE_PASSWORD:allcheck_local_6}",
        "spring.jpa.hibernate.ddl-auto=validate",
        "spring.jpa.show-sql=false",
    ],
)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class IdentityPersistenceTest @Autowired constructor(
    private val accounts: AccountRepository,
    private val sessions: AuthSessionRepository,
    private val entityManager: EntityManager,
    private val jdbc: JdbcTemplate,
    transactionManager: PlatformTransactionManager,
) {
    private val createdAt = Instant.parse("2026-11-16T00:00:00Z")
    private val expiresAt = createdAt.plus(Duration.ofDays(14))
    private val initialHash = RefreshTokenHash("a".repeat(64))
    private val transactions = TransactionTemplate(transactionManager)

    @Test
    fun `계정과 세션의 식별자는 저장할 때 DB가 양수 BIGINT로 생성한다`() {
        // given
        val account = Account.driver(SocialLogin.kakao(123456790), createdAt)
        assertThat(account.id).isNull()

        // when
        accounts.saveAndFlush(account)
        val session = AuthSession(checkNotNull(account.id), initialHash, createdAt, expiresAt)
        assertThat(session.id).isNull()
        sessions.saveAndFlush(session)

        // then
        assertThat(checkNotNull(account.id)).isPositive()
        assertThat(checkNotNull(session.id)).isPositive()
        val generatedKeys = mapOf(
            "identity_accounts" to "account_id",
            "identity_auth_sessions" to "session_id",
            "identity_push_devices" to "device_id",
            "drivers" to "driver_id",
            "driver_businesses" to "business_id",
            "driver_registration_history" to "history_id",
            "driver_idempotency_records" to "record_id",
            "driver_notification_events" to "event_id",
            "driver_push_deliveries" to "delivery_id",
            "work_orders" to "work_id",
        )
        generatedKeys.forEach { (table, column) ->
            val metadata = jdbc.queryForMap(
                "select data_type, is_identity from information_schema.columns where table_schema = current_schema() and table_name = ? and column_name = ?",
                table,
                column,
            )
            assertThat(metadata).containsEntry("data_type", "bigint").containsEntry("is_identity", "YES")
        }
    }

    @Test
    fun `Flyway 스키마에서 관리자와 카카오 계정의 자격 증명 및 비활성 상태를 복원한다`() {
        // given
        val driver = accounts.save(Account.driver(SocialLogin.kakao(123456789), createdAt))
        driver.disable(createdAt.plusSeconds(60))
        val credential = AdminCredential("admin01", "encoded-test-password", AdminRole.SUB)
        val admin = accounts.save(Account.administrator(credential, createdAt))
        entityManager.flush()
        entityManager.clear()

        // when
        val storedDriver = accounts.findBySocialLogin(SocialLogin.kakao(123456789))
        val storedAdmin = accounts.findByAdminCredentialLoginId("admin01")

        // then
        assertThat(storedDriver?.id).isEqualTo(checkNotNull(driver.id))
        assertThat(storedDriver?.userType).isEqualTo(UserType.DRIVER)
        assertThat(storedDriver?.enabled).isFalse()
        assertThat(storedDriver?.disabledAt).isEqualTo(createdAt.plusSeconds(60))
        assertThat(storedDriver?.adminCredential).isNull()
        assertThat(storedAdmin?.id).isEqualTo(checkNotNull(admin.id))
        assertThat(storedAdmin?.adminCredential?.role).isEqualTo(AdminRole.SUB)
        assertThat(storedAdmin?.adminCredential?.passwordHash).isEqualTo(credential.passwordHash)
        assertThat(storedAdmin?.socialLogin).isNull()
    }

    @Test
    fun `동일한 카카오 회원번호의 중복 계정은 DB 제약으로 거부한다`() {
        // given
        accounts.saveAndFlush(Account.driver(SocialLogin.kakao(123456789), createdAt))

        // when & then
        assertThatThrownBy {
            accounts.saveAndFlush(Account.driver(SocialLogin.kakao(123456789), createdAt))
        }.isInstanceOf(DataIntegrityViolationException::class.java)
    }

    @Test
    fun `관리자 로그인 ID의 중복은 DB 제약으로 거부한다`() {
        // given
        val credential = AdminCredential("admin01", "encoded-test-password", AdminRole.MASTER)
        accounts.saveAndFlush(Account.administrator(credential, createdAt))

        // when & then
        assertThatThrownBy {
            accounts.saveAndFlush(Account.administrator(credential, createdAt))
        }.isInstanceOf(DataIntegrityViolationException::class.java)
    }

    @Test
    fun `기사 계정에 관리자 자격 증명을 넣는 직접 SQL도 거부한다`() {
        // when & then
        assertThatThrownBy {
            jdbc.update(
                """
                INSERT INTO identity_accounts
                    (user_type, social_provider, social_user_id, login_id, created_at)
                VALUES ('DRIVER', 'KAKAO', '123456789', 'unexpected-admin', ?)
                """.trimIndent(),
                Timestamp.from(createdAt),
            )
        }.isInstanceOf(DataIntegrityViolationException::class.java)
    }

    @Test
    fun `여러 기기 세션을 저장하고 갱신과 폐기 상태를 복원한다`() {
        // given
        val account = accounts.saveAndFlush(Account.driver(SocialLogin.kakao(123456789), createdAt))
        val first = sessions.save(AuthSession(checkNotNull(account.id), initialHash, createdAt, expiresAt))
        val other = sessions.save(AuthSession(checkNotNull(account.id), RefreshTokenHash("b".repeat(64)), createdAt, expiresAt))
        val replacement = RefreshTokenHash("c".repeat(64))
        first.rotateRefreshToken(initialHash, replacement, createdAt.plusSeconds(900))
        other.revoke(createdAt.plusSeconds(60))
        entityManager.flush()
        entityManager.clear()

        // when
        val stored = sessions.findByRefreshTokenHash(replacement)
        val storedOther = sessions.findById(checkNotNull(other.id)).orElseThrow()

        // then
        assertThat(sessions.findAllByAccountId(checkNotNull(account.id))).hasSize(2)
        assertThat(sessions.findByRefreshTokenHash(initialHash)).isNull()
        assertThat(stored?.id).isEqualTo(checkNotNull(first.id))
        assertThat(stored?.lastRefreshedAt).isEqualTo(createdAt.plusSeconds(900))
        assertThat(stored?.expiresAt).isEqualTo(expiresAt)
        assertThat(storedOther.isActiveAt(createdAt.plusSeconds(900))).isFalse()
    }

    @Test
    fun `다른 세션에 동일한 Refresh Token 해시를 저장할 수 없다`() {
        // given
        val account = accounts.saveAndFlush(Account.driver(SocialLogin.kakao(123456789), createdAt))
        sessions.saveAndFlush(AuthSession(checkNotNull(account.id), initialHash, createdAt, expiresAt))

        // when & then
        assertThatThrownBy {
            sessions.saveAndFlush(AuthSession(checkNotNull(account.id), initialHash, createdAt, expiresAt))
        }.isInstanceOf(DataIntegrityViolationException::class.java)
    }

    @Test
    fun `존재하지 않는 인증 계정의 세션은 저장할 수 없다`() {
        // when & then
        assertThatThrownBy {
            sessions.saveAndFlush(AuthSession(Long.MAX_VALUE, initialHash, createdAt, expiresAt))
        }.isInstanceOf(DataIntegrityViolationException::class.java)
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    fun `독립 트랜잭션의 동시 토큰 갱신은 한 번만 커밋된다`() {
        // given
        val account = transactions.execute {
            accounts.saveAndFlush(Account.driver(SocialLogin.kakao(987654321), createdAt))
        }
        val session = transactions.execute {
            sessions.saveAndFlush(AuthSession(checkNotNull(account.id), initialHash, createdAt, expiresAt))
        }
        val readBarrier = CountDownLatch(2)
        val executor = Executors.newFixedThreadPool(2)
        val replacements = listOf(RefreshTokenHash("b".repeat(64)), RefreshTokenHash("c".repeat(64)))

        try {
            // when
            val results = replacements.map { replacement ->
                executor.submit<Boolean> {
                    try {
                        transactions.execute {
                            val current = sessions.findById(checkNotNull(session.id)).orElseThrow()
                            readBarrier.countDown()
                            check(readBarrier.await(5, TimeUnit.SECONDS)) { "Concurrent readers did not reach the barrier." }
                            check(current.rotateRefreshToken(initialHash, replacement, createdAt.plusSeconds(900)))
                            sessions.saveAndFlush(current)
                            true
                        } == true
                    } catch (_: OptimisticLockingFailureException) {
                        false
                    }
                }
            }.map { it.get(15, TimeUnit.SECONDS) }
            val stored = transactions.execute { sessions.findById(checkNotNull(session.id)).orElseThrow() }
                ?: error("Session fixture was not found.")

            // then
            assertThat(results.count { it }).isEqualTo(1)
            assertThat(stored.refreshTokenHash).isIn(replacements)
            assertThat(stored.version).isEqualTo(1)
            assertThat(stored.expiresAt).isEqualTo(expiresAt)
        } finally {
            executor.shutdownNow()
            check(executor.awaitTermination(10, TimeUnit.SECONDS)) { "Concurrent workers did not stop." }
            transactions.executeWithoutResult {
                sessions.deleteAllByIdInBatch(listOf(checkNotNull(session.id)))
                accounts.deleteById(checkNotNull(account.id))
            }
        }
    }
}
