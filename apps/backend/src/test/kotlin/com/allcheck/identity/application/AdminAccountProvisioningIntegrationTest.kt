package com.allcheck.identity.application

import com.allcheck.global.exception.BusinessException
import com.allcheck.identity.AdminRole
import com.allcheck.identity.UserType
import com.allcheck.identity.application.login.AdminLoginService
import com.allcheck.identity.application.provisioning.AdminAccountProvisioningService
import com.allcheck.identity.application.provisioning.AdminAccountProvisioningTransactions
import com.allcheck.identity.application.provisioning.ProvisionedAdministrator
import com.allcheck.identity.exception.IdentityErrorCode
import com.allcheck.identity.infrastructure.login.password.AdminPasswordHasher
import com.allcheck.identity.infrastructure.repository.account.AccountRepository
import com.allcheck.identity.infrastructure.repository.session.AuthSessionRepository
import com.allcheck.support.PostgresTestSchema
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import org.junit.jupiter.params.provider.ValueSource
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mockito
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.annotation.DirtiesContext
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.context.TestPropertySource
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionSynchronizationManager
import org.springframework.transaction.support.TransactionTemplate
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.sql.DataSource

@SpringBootTest(properties = ["spring.jpa.show-sql=false"])
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@TestPropertySource("classpath:jwt-test.properties")
class AdminAccountProvisioningIntegrationTest @Autowired constructor(
    private val provisioning: AdminAccountProvisioningService,
    private val writer: AdminAccountProvisioningTransactions,
    private val login: AdminLoginService,
    private val accounts: AccountRepository,
    private val sessions: AuthSessionRepository,
    transactionManager: PlatformTransactionManager,
) {
    @MockitoSpyBean private lateinit var passwords: AdminPasswordHasher

    private val tx = TransactionTemplate(transactionManager)
    private val ownLoginIds = mutableListOf<String>()
    private val password = "provisioned-test-password"

    @AfterEach
    fun removeOnlyOwnFixtures() {
        tx.executeWithoutResult {
            ownLoginIds.forEach { id ->
                val account = accounts.findByAdminCredentialLoginId(id) ?: return@forEach
                sessions.deleteAllByIdInBatch(sessions.findAllByAccountId(checkNotNull(account.id)).map { checkNotNull(it.id) })
                accounts.deleteById(checkNotNull(account.id))
            }
        }
    }

    @ParameterizedTest
    @EnumSource(AdminRole::class)
    fun `명시한 역할의 관리자만 생성하고 발급 계정으로 실제 로그인한다`(role: AdminRole) {
        // given
        val id = nextLoginId()

        // when
        val created = provisioning.provision(id, password, role)

        // then
        val account = checkNotNull(accounts.findByAdminCredentialLoginId(id))
        val credential = checkNotNull(account.adminCredential)
        assertThat(checkNotNull(account.id)).isEqualTo(created.accountId)
        assertThat(account.userType).isEqualTo(UserType.ADMIN)
        assertThat(credential.role).isEqualTo(role)
        assertThat(credential.passwordHash).doesNotContain(password)
        assertThat(passwords.matches(password, credential.passwordHash)).isTrue()
        assertThat(sessions.findAllByAccountId(checkNotNull(account.id))).isEmpty()
        assertThat(login.login(id, password).user.role).isEqualTo(role)
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `기존 아이디는 비활성 여부와 무관하게 비밀번호 역할 세션을 덮어쓰지 않는다`(disabled: Boolean) {
        // given
        val id = nextLoginId()
        val original = provisioning.provision(id, password, AdminRole.MASTER)
        login.login(id, password)
        if (disabled) tx.executeWithoutResult { checkNotNull(accounts.findByIdForUpdate(original.accountId)).disable(Instant.now()) }
        val before = accounts.findById(original.accountId).orElseThrow()
        val sessionIds = sessions.findAllByAccountId(checkNotNull(before.id)).map { checkNotNull(it.id) }

        // when & then
        assertThatThrownBy { provisioning.provision(id, "different-password", AdminRole.SUB) }
            .isInstanceOfSatisfying(BusinessException::class.java) { assertThat(it.errorCode).isEqualTo(IdentityErrorCode.ADMIN_LOGIN_ID_ALREADY_EXISTS) }
        val after = accounts.findByAdminCredentialLoginId(id)
        assertThat(after?.id).isEqualTo(checkNotNull(before.id))
        assertThat(after?.adminCredential?.passwordHash).isEqualTo(before.adminCredential?.passwordHash)
        assertThat(after?.adminCredential?.role).isEqualTo(AdminRole.MASTER)
        assertThat(after?.enabled).isEqualTo(!disabled)
        assertThat(after?.version).isEqualTo(before.version)
        assertThat(sessions.findAllByAccountId(checkNotNull(before.id)).map { checkNotNull(it.id) }).containsExactlyElementsOf(sessionIds)
    }

    @Test
    fun `해시 생성은 호출자 쓰기 트랜잭션을 중단한 상태에서 수행한다`() {
        // given
        val id = nextLoginId()
        Mockito.doAnswer {
            check(!TransactionSynchronizationManager.isActualTransactionActive())
            it.callRealMethod()
        }.`when`(passwords).encode(anyString())

        // when
        val created = tx.execute { provisioning.provision(id, password, AdminRole.SUB) }

        // then
        assertThat(accounts.existsById(created.accountId)).isTrue()
    }

    @Test
    fun `발급 저장은 호출자 트랜잭션이 실패하면 함께 롤백된다`() {
        // given
        val id = nextLoginId()
        val hash = passwords.encode(password)

        // when & then
        assertThatThrownBy {
            tx.executeWithoutResult {
                writer.create(id, hash, AdminRole.MASTER)
                check(accounts.findByAdminCredentialLoginId(id) != null)
                throw IllegalStateException("test-rollback")
            }
        }.isInstanceOf(IllegalStateException::class.java)
        assertThat(accounts.findByAdminCredentialLoginId(id)).isNull()
    }

    @Test
    fun `같은 아이디의 동시 발급은 한 계정만 만들고 기존 값을 보존한다`() {
        // given
        val id = nextLoginId()
        val ready = CountDownLatch(2)
        val executor = Executors.newFixedThreadPool(2)
        try {
            // when
            val results = listOf(AdminRole.MASTER, AdminRole.SUB).map { role ->
                executor.submit<Any> {
                    ready.countDown()
                    check(ready.await(5, TimeUnit.SECONDS))
                    try {
                        provisioning.provision(id, password, role)
                    } catch (ex: BusinessException) {
                        ex.errorCode
                    }
                }
            }.map { it.get(20, TimeUnit.SECONDS) }

            // then
            val winner = results.filterIsInstance<ProvisionedAdministrator>().single()
            assertThat(results.filterIsInstance<IdentityErrorCode>()).containsExactly(IdentityErrorCode.ADMIN_LOGIN_ID_ALREADY_EXISTS)
            val account = checkNotNull(accounts.findByAdminCredentialLoginId(id))
            assertThat(checkNotNull(account.id)).isEqualTo(winner.accountId)
            assertThat(account.adminCredential?.role).isEqualTo(winner.role)
            assertThat(sessions.findAllByAccountId(checkNotNull(account.id))).isEmpty()
        } finally {
            executor.shutdownNow()
            check(executor.awaitTermination(10, TimeUnit.SECONDS))
        }
    }

    private fun nextLoginId(): String = "provision-${UUID.randomUUID()}".also { ownLoginIds.add(it) }

    companion object {
        private val database = PostgresTestSchema("admin_provisioning")

        @JvmStatic
        @DynamicPropertySource
        fun properties(registry: DynamicPropertyRegistry) = database.register(registry)

        @JvmStatic
        @AfterAll
        fun removeTestSchema(@Autowired dataSource: DataSource) = database.remove(dataSource)
    }
}
