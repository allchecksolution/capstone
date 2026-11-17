package com.allcheck.identity.application

import com.allcheck.global.exception.BusinessException
import com.allcheck.identity.AdminRole
import com.allcheck.identity.IdentityAccountOperations
import com.allcheck.identity.IdentityOperations
import com.allcheck.identity.UserType
import com.allcheck.identity.domain.entity.Account
import com.allcheck.identity.domain.entity.AuthSession
import com.allcheck.identity.domain.vo.AdminCredential
import com.allcheck.identity.domain.vo.SocialLogin
import com.allcheck.identity.exception.IdentityErrorCode
import com.allcheck.identity.infrastructure.repository.AccountRepository
import com.allcheck.identity.infrastructure.repository.AuthSessionRepository
import com.allcheck.identity.infrastructure.token.RefreshTokenProvider
import com.allcheck.identity.presentation.dto.response.TokenPairResponse
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.test.context.TestPropertySource
import org.springframework.transaction.IllegalTransactionStateException
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@SpringBootTest(
    properties = [
        "spring.datasource.url=\${IDENTITY_TEST_DATABASE_URL:jdbc:postgresql://localhost:15432/allcheck_identity_test}",
        "spring.datasource.username=\${IDENTITY_TEST_DATABASE_USER:allcheck}",
        "spring.datasource.password=\${IDENTITY_TEST_DATABASE_PASSWORD:allcheck_local_6}",
        "spring.jpa.show-sql=false",
    ],
)
@TestPropertySource("classpath:jwt-test.properties")
@Import(DriverAccountLifecycleIntegrationTest.TestConfig::class)
class DriverAccountLifecycleIntegrationTest @Autowired constructor(
    private val accountLifecycle: IdentityAccountOperations,
    private val authentication: IdentityOperations,
    private val login: KakaoSessionService,
    private val lifecycle: SessionLifecycleService,
    private val accounts: AccountRepository,
    private val sessions: AuthSessionRepository,
    private val refreshTokens: RefreshTokenProvider,
    clock: Clock,
    transactionManager: PlatformTransactionManager,
) {
    private val now = clock.instant()
    private val transactions = TransactionTemplate(transactionManager)
    private val ownAccounts = mutableListOf<String>()

    @AfterEach
    fun removeOwnFixtures() {
        transactions.executeWithoutResult {
            ownAccounts.forEach { accountId ->
                sessions.deleteAllByIdInBatch(sessions.findAllByAccountId(accountId).map { it.id })
                accounts.deleteById(accountId)
            }
        }
    }

    @Test
    fun `기사 비활성화는 모든 세션을 폐기하고 다른 계정과 기존 자격 증명을 보존한다`() {
        // given
        val account = saveDriver()
        val active = session(account.id)
        val second = session(account.id)
        val expired = session(account.id, now.minusSeconds(1))
        val revoked = session(account.id)
        transactions.executeWithoutResult { sessions.findById(revoked.id).orElseThrow().revoke(now.minusSeconds(60)) }
        val otherAccount = saveDriver()
        val otherSession = session(otherAccount.id)

        // when
        accountLifecycle.deactivateDriverAccount(account.id)

        // then
        val disabled = accounts.findById(account.id).orElseThrow()
        assertThat(disabled.enabled).isFalse()
        assertThat(disabled.disabledAt).isEqualTo(now)
        assertThat(disabled.socialLogin?.provider).isEqualTo(account.socialLogin?.provider)
        assertThat(disabled.socialLogin?.providerUserId).isEqualTo(account.socialLogin?.providerUserId)
        listOf(active, second, expired).forEach { session ->
            assertThat(sessions.findById(session.id).orElseThrow().revokedAt).isEqualTo(now)
            assertThat(authentication.findAuthenticatedAccount(account.id, session.id)).isNull()
        }
        assertThat(sessions.findById(revoked.id).orElseThrow().revokedAt).isEqualTo(now.minusSeconds(60))
        assertThat(authentication.findAuthenticatedAccount(otherAccount.id, otherSession.id)).isNotNull()
        val versions = sessions.findAllByAccountId(account.id).associate { it.id to it.version }

        // when & then: 반복 호출도 기존 폐기 시각과 버전을 바꾸지 않는다.
        accountLifecycle.deactivateDriverAccount(account.id)
        assertThat(sessions.findAllByAccountId(account.id).associate { it.id to it.version }).isEqualTo(versions)
        assertThat(accounts.findById(account.id).orElseThrow().version).isEqualTo(disabled.version)
    }

    @Test
    fun `호출자 트랜잭션의 실패는 계정 비활성화와 모든 세션 폐기를 함께 롤백한다`() {
        // given
        val account = saveDriver()
        val session = session(account.id)

        // when
        assertThatThrownBy {
            transactions.executeWithoutResult {
                accountLifecycle.deactivateDriverAccount(account.id)
                sessions.flush()
                throw IllegalStateException("test-driver-withdrawal-rollback")
            }
        }.isInstanceOf(IllegalStateException::class.java)

        // then
        assertThat(accounts.findById(account.id).orElseThrow().enabled).isTrue()
        assertThat(sessions.findById(session.id).orElseThrow().revokedAt).isNull()
        assertThat(authentication.findAuthenticatedAccount(account.id, session.id)).isNotNull()
    }

    @Test
    fun `활성 기사 잠금은 호출자 트랜잭션을 요구하고 공개 계약만 반환한다`() {
        // given
        val account = saveDriver()

        // when & then
        assertThatThrownBy { accountLifecycle.lockActiveDriverAccount(account.id) }
            .isInstanceOf(IllegalTransactionStateException::class.java)
        val result = transactions.execute { accountLifecycle.lockActiveDriverAccount(account.id) }
        assertThat(result.accountId).isEqualTo(account.id)
        assertThat(result.userType).isEqualTo(UserType.DRIVER)
        assertThat(result.role).isNull()

        accountLifecycle.deactivateDriverAccount(account.id)
        assertBusinessError(IdentityErrorCode.ACCOUNT_DISABLED) {
            transactions.execute { accountLifecycle.lockActiveDriverAccount(account.id) }
        }
    }

    @ParameterizedTest
    @ValueSource(strings = ["lock", "deactivate"])
    fun `기사 수명주기 API로 관리자 계정을 변경할 수 없다`(operation: String) {
        // given
        val account = transactions.execute {
            accounts.saveAndFlush(Account.administrator(AdminCredential("stage7-admin", "test-password-hash", AdminRole.MASTER), now.minus(Duration.ofDays(20))))
        }
        ownAccounts.add(account.id)
        val session = session(account.id)

        // when & then
        assertBusinessError(IdentityErrorCode.DRIVER_ACCOUNT_REQUIRED) {
            transactions.execute {
                if (operation == "lock") accountLifecycle.lockActiveDriverAccount(account.id) else accountLifecycle.deactivateDriverAccount(account.id)
            }
        }
        assertThat(accounts.findById(account.id).orElseThrow().enabled).isTrue()
        assertThat(sessions.findById(session.id).orElseThrow().revokedAt).isNull()
    }

    @ParameterizedTest
    @ValueSource(strings = ["lock", "deactivate"])
    fun `존재하지 않는 인증 계정은 명시적인 오류를 반환한다`(operation: String) {
        // when & then
        assertBusinessError(IdentityErrorCode.ACCOUNT_NOT_FOUND) {
            transactions.execute {
                if (operation == "lock") accountLifecycle.lockActiveDriverAccount("missing") else accountLifecycle.deactivateDriverAccount("missing")
            }
        }
    }

    @Test
    fun `카카오 재로그인과 비활성화가 경쟁해도 최종 계정은 비활성이고 새 세션도 사용할 수 없다`() {
        // given
        val account = saveDriver()
        val socialLogin = checkNotNull(account.socialLogin)
        val existing = session(account.id)
        val barrier = CountDownLatch(2)
        val executor = Executors.newFixedThreadPool(2)
        try {
            // when
            val loginResult = executor.submit<Any> {
                barrier.countDown()
                check(barrier.await(5, TimeUnit.SECONDS))
                try {
                    login.login(socialLogin)
                } catch (ex: BusinessException) {
                    ex.errorCode
                }
            }
            val deactivation = executor.submit {
                barrier.countDown()
                check(barrier.await(5, TimeUnit.SECONDS))
                accountLifecycle.deactivateDriverAccount(account.id)
            }
            val result = loginResult.get(15, TimeUnit.SECONDS)
            deactivation.get(15, TimeUnit.SECONDS)

            // then
            assertThat(accounts.findBySocialLogin(socialLogin)?.id).isEqualTo(account.id)
            assertThat(accounts.findById(account.id).orElseThrow().enabled).isFalse()
            assertThat(sessions.findAllByAccountId(account.id)).allMatch { it.revokedAt != null }
            assertThat(authentication.findAuthenticatedAccount(account.id, existing.id)).isNull()
            if (result is TokenPairResponse) {
                assertBusinessError(IdentityErrorCode.REFRESH_TOKEN_INVALID) { lifecycle.refresh(result.refreshToken) }
            } else {
                assertThat(result).isEqualTo(IdentityErrorCode.ACCOUNT_DISABLED)
            }
            assertBusinessError(IdentityErrorCode.ACCOUNT_DISABLED) { login.login(socialLogin) }
        } finally {
            executor.shutdownNow()
            check(executor.awaitTermination(10, TimeUnit.SECONDS))
        }
    }

    private fun saveDriver(): Account {
        val saved = transactions.execute {
            accounts.saveAndFlush(Account.driver(SocialLogin.kakao(700000001L + ownAccounts.size), now.minus(Duration.ofDays(20))))
        }
        ownAccounts.add(saved.id)
        return saved
    }

    private fun session(accountId: String, expiresAt: Instant = now.plus(Duration.ofDays(1))): AuthSession = transactions.execute {
        sessions.saveAndFlush(AuthSession(accountId, refreshTokens.hash(refreshTokens.generate()), now.minus(Duration.ofDays(2)), expiresAt))
    }

    private fun assertBusinessError(expected: IdentityErrorCode, action: () -> Unit) {
        assertThatThrownBy { action() }.isInstanceOfSatisfying(BusinessException::class.java) { ex ->
            assertThat(ex.errorCode).isEqualTo(expected)
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    class TestConfig {
        @Bean
        fun clock(): Clock = Clock.fixed(Instant.parse("2026-11-18T00:00:00Z"), ZoneOffset.UTC)
    }
}
