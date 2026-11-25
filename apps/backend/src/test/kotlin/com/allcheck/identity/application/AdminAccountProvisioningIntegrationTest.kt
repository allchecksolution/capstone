package com.allcheck.identity.application

import com.allcheck.global.exception.BusinessException
import com.allcheck.identity.AdminRole
import com.allcheck.identity.application.account.AdminAccountService
import com.allcheck.identity.application.account.AdminAccountTransactions
import com.allcheck.identity.application.login.AdminLoginService
import com.allcheck.identity.application.login.AdminSessionService
import com.allcheck.identity.application.provisioning.AdminAccountProvisioningService
import com.allcheck.identity.application.session.SessionLifecycleService
import com.allcheck.identity.domain.entity.account.Account
import com.allcheck.identity.domain.vo.account.AdminCredential
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
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.annotation.DirtiesContext
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.context.TestPropertySource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.sql.DataSource

@SpringBootTest(properties = ["spring.jpa.show-sql=false"])
@AutoConfigureMockMvc
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@TestPropertySource("classpath:jwt-test.properties")
class AdminAccountProvisioningIntegrationTest @Autowired constructor(
    private val provisioning: AdminAccountProvisioningService,
    private val management: AdminAccountService,
    private val writes: AdminAccountTransactions,
    private val login: AdminLoginService,
    private val loginSessions: AdminSessionService,
    private val lifecycle: SessionLifecycleService,
    private val accounts: AccountRepository,
    private val sessions: AuthSessionRepository,
    private val passwords: AdminPasswordHasher,
    private val jdbc: JdbcTemplate,
    private val mvc: MockMvc,
    transactionManager: PlatformTransactionManager,
) {
    private val tx = TransactionTemplate(transactionManager)
    private val password = "test-initial-password"

    @AfterEach
    fun clearOwnedSchemaFixtures() {
        // The datasource is constrained to this class's UUID-named test schema.
        jdbc.execute("TRUNCATE identity_accounts CASCADE")
    }

    @Test
    fun `최초 발급은 한번만 가능하며 첫 로그인 비밀번호 변경을 강제하지 않는다`() {
        val first = provisioning.provision("root", password, AdminRole.MASTER)
        assertThat(accounts.findById(first.accountId).orElseThrow().isBootstrapAdmin).isTrue()
        assertThat(login.login("root", password).user.isBootstrapAdmin).isTrue()
        assertThatThrownBy { provisioning.provision("other", password, AdminRole.MASTER) }
            .isInstanceOfSatisfying(BusinessException::class.java) { assertThat(it.errorCode).isEqualTo(IdentityErrorCode.BOOTSTRAP_ADMIN_ALREADY_EXISTS) }
        assertThat(accounts.findByAdminCredentialLoginId("other")).isNull()
    }

    @Test
    fun `최초 관리자는 추가 계정을 수정 비활성화하고 최초 계정은 보호한다`() {
        val root = provisioning.provision("root", password, AdminRole.MASTER)
        val child = management.create(root.accountId, "child", password)
        val childLogin = login.login("child", password)
        val updated = management.update(root.accountId, child.accountId, child.version, "renamed", "reset-password")
        assertThat(updated.loginId).isEqualTo("renamed")
        assertThat(sessions.findAllByAccountId(child.accountId)).allMatch { it.revokedAt != null }
        mvc.perform(get("/api/v1/auth/accounts/me").header("Authorization", "Bearer ${childLogin.accessToken}")).andExpect(status().isUnauthorized)
        val next = login.login("renamed", "reset-password")
        assertThatThrownBy { lifecycle.refresh(childLogin.refreshToken) }.isInstanceOf(BusinessException::class.java)
        writes.deactivate(root.accountId, child.accountId, updated.version)
        mvc.perform(get("/api/v1/auth/accounts/me").header("Authorization", "Bearer ${next.accessToken}")).andExpect(status().isUnauthorized)
        assertThat(accounts.findById(child.accountId).orElseThrow().enabled).isFalse()
        assertThatThrownBy { management.create(root.accountId, "renamed", password) }.isInstanceOf(BusinessException::class.java)
        val rootVersion = accounts.findById(root.accountId).orElseThrow().version!!
        assertThatThrownBy { writes.deactivate(root.accountId, root.accountId, rootVersion) }
            .isInstanceOfSatisfying(BusinessException::class.java) { assertThat(it.errorCode).isEqualTo(IdentityErrorCode.BOOTSTRAP_ADMIN_PROTECTED) }
        assertThatThrownBy { management.update(root.accountId, root.accountId, rootVersion, "other", null) }.isInstanceOf(BusinessException::class.java)
        assertThatThrownBy {
            tx.executeWithoutResult {
                accounts.deleteById(root.accountId)
                accounts.flush()
            }
        }.isInstanceOf(Exception::class.java)
        assertThat(jdbc.queryForObject("SELECT count(*) FROM identity_admin_history", Long::class.java)).isEqualTo(4)
    }

    @Test
    fun `실제 토큰으로 최초 관리자만 생성 수정 삭제할 수 있고 역할 승격 필드는 거절한다`() {
        val root = provisioning.provision("root", password, AdminRole.MASTER)
        val rootToken = login.login("root", password).accessToken
        mvc.perform(get("/api/v1/auth/accounts/me").header("Authorization", "Bearer $rootToken"))
            .andExpect(status().isOk).andExpect(jsonPath("$.isBootstrapAdmin").value(true))
        mvc.perform(
            post("/api/v1/admin-accounts").header("Authorization", "Bearer $rootToken").contentType(MediaType.APPLICATION_JSON)
                .content("""{"loginId":"child","initialPassword":"$password"}"""),
        )
            .andExpect(status().isCreated).andExpect(jsonPath("$.isBootstrapAdmin").value(false))
        val child = accounts.findByAdminCredentialLoginId("child")!!
        val childToken = login.login("child", password).accessToken
        mvc.perform(
            post("/api/v1/admin-accounts").header("Authorization", "Bearer $childToken").contentType(MediaType.APPLICATION_JSON)
                .content("""{"loginId":"third","initialPassword":"$password"}"""),
        )
            .andExpect(status().isForbidden)
        mvc.perform(get("/api/v1/admin-accounts").header("Authorization", "Bearer $childToken")).andExpect(status().isForbidden)
        mvc.perform(
            put("/api/v1/admin-accounts/${child.id}").header("Authorization", "Bearer $childToken").contentType(MediaType.APPLICATION_JSON)
                .content("""{"expectedVersion":${child.version},"loginId":"third"}"""),
        )
            .andExpect(status().isForbidden)
        mvc.perform(delete("/api/v1/admin-accounts/${root.accountId}").param("expectedVersion", "0").header("Authorization", "Bearer $childToken"))
            .andExpect(status().isForbidden)
        mvc.perform(delete("/api/v1/admin-accounts/${root.accountId}").param("expectedVersion", "0").header("Authorization", "Bearer $rootToken"))
            .andExpect(status().isForbidden)
        mvc.perform(post("/api/v1/admin-accounts").contentType(MediaType.APPLICATION_JSON).content("{}"))
            .andExpect(status().isUnauthorized)
        mvc.perform(
            post("/api/v1/admin-accounts").header("Authorization", "Bearer $rootToken").contentType(MediaType.APPLICATION_JSON)
                .content("""{"loginId":"third","initialPassword":"$password","isBootstrapAdmin":true}"""),
        )
            .andExpect(status().isBadRequest)
        mvc.perform(get("/api/v1/drivers").header("Authorization", "Bearer $childToken")).andExpect(status().isOk)
    }

    @Test
    fun `복구와 본인 비밀번호 변경은 이전 세션을 무효화한다`() {
        val root = provisioning.provision("root", password, AdminRole.MASTER)
        val previous = login.login("root", password)
        val verifiedBeforeReset = loginSessions.candidate("root")!!
        provisioning.recover("root", "recovered-password")
        assertThatThrownBy { loginSessions.login(verifiedBeforeReset) }.isInstanceOf(BusinessException::class.java)
        assertThatThrownBy { login.login("root", password) }.isInstanceOf(BusinessException::class.java)
        mvc.perform(get("/api/v1/auth/accounts/me").header("Authorization", "Bearer ${previous.accessToken}")).andExpect(status().isUnauthorized)
        val recovered = login.login("root", "recovered-password")
        management.changeOwnPassword(root.accountId, "recovered-password", "own-new-password")
        assertThatThrownBy { lifecycle.refresh(recovered.refreshToken) }.isInstanceOf(BusinessException::class.java)
        assertThat(login.login("root", "own-new-password").user.isBootstrapAdmin).isTrue()
        assertThatThrownBy { provisioning.recover("different", password) }.isInstanceOf(BusinessException::class.java)
    }

    @Test
    fun `계정 변경 실패는 자격 증명 세션 이력을 함께 롤백한다`() {
        val root = provisioning.provision("root", password, AdminRole.MASTER)
        val child = management.create(root.accountId, "child", password)
        val before = login.login("child", password)
        assertThatThrownBy {
            tx.executeWithoutResult {
                writes.update(root.accountId, child.accountId, child.version, "changed", passwords.encode("changed-password"))
                throw IllegalStateException("rollback")
            }
        }.isInstanceOf(IllegalStateException::class.java)
        assertThat(accounts.findByAdminCredentialLoginId("changed")).isNull()
        mvc.perform(get("/api/v1/auth/accounts/me").header("Authorization", "Bearer ${before.accessToken}")).andExpect(status().isOk)
        assertThat(jdbc.queryForObject("SELECT count(*) FROM identity_admin_history", Long::class.java)).isEqualTo(2)
    }

    @Test
    fun `복구와 이전 비밀번호 로그인 및 갱신의 경쟁에서 이전 세션은 남지 않는다`() {
        val root = provisioning.provision("root", password, AdminRole.MASTER)
        val initial = login.login("root", password)
        val executor = Executors.newFixedThreadPool(3)
        val ready = CountDownLatch(3)
        try {
            val operations: List<() -> Unit> = listOf(
                {
                    provisioning.recover("root", "new-secret")
                    Unit
                },
                {
                    try {
                        login.login("root", password)
                    } catch (ex: BusinessException) {
                        assertThat(ex.errorCode).isEqualTo(IdentityErrorCode.INVALID_CREDENTIALS)
                    }
                    Unit
                },
                {
                    try {
                        lifecycle.refresh(initial.refreshToken)
                    } catch (ex: BusinessException) {
                        assertThat(ex.errorCode).isEqualTo(IdentityErrorCode.REFRESH_TOKEN_INVALID)
                    }
                    Unit
                },
            )
            val pending = operations.map { operation ->
                executor.submit {
                    ready.countDown()
                    check(ready.await(5, TimeUnit.SECONDS))
                    operation()
                }
            }
            pending.forEach { it.get(30, TimeUnit.SECONDS) }
            assertThat(sessions.findAllByAccountId(root.accountId)).allMatch { it.revokedAt != null }
            assertThat(login.login("root", "new-secret").user.isBootstrapAdmin).isTrue()
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun `기존 계정은 명시적으로 하나만 최초 계정으로 지정한다`() {
        val hash = passwords.encode(password)
        tx.executeWithoutResult { accounts.saveAndFlush(Account.administrator(AdminCredential("legacy", hash, AdminRole.SUB), Instant.now())) }
        assertThat(accounts.findByAdminCredentialLoginId("legacy")!!.isBootstrapAdmin).isFalse()
        assertThatThrownBy { provisioning.adopt("legacy", "wrong") }.isInstanceOf(BusinessException::class.java)
        provisioning.adopt("legacy", password)
        assertThat(login.login("legacy", password).user.isBootstrapAdmin).isTrue()
        assertThatThrownBy { provisioning.adopt("legacy", password) }.isInstanceOf(BusinessException::class.java)
    }

    @Test
    fun `서로 다른 아이디로 동시에 초기화해도 최초 계정은 하나뿐이다`() {
        val executor = Executors.newFixedThreadPool(2)
        val latch = CountDownLatch(2)
        try {
            val results = listOf("one", "two").map { id ->
                executor.submit<Boolean> {
                    latch.countDown()
                    check(latch.await(5, TimeUnit.SECONDS))
                    try {
                        provisioning.provision(id, password, AdminRole.MASTER)
                        true
                    } catch (ex: BusinessException) {
                        assertThat(ex.errorCode).isEqualTo(IdentityErrorCode.BOOTSTRAP_ADMIN_ALREADY_EXISTS)
                        false
                    }
                }
            }.map { it.get(30, TimeUnit.SECONDS) }
            assertThat(results.count { it }).isEqualTo(1)
            assertThat(accounts.count()).isEqualTo(1)
        } finally {
            executor.shutdownNow()
        }
    }

    companion object {
        private val database = PostgresTestSchema("admin_management")

        @JvmStatic @DynamicPropertySource
        fun properties(registry: DynamicPropertyRegistry) = database.register(registry)

        @JvmStatic @AfterAll
        fun removeTestSchema(@Autowired dataSource: DataSource) = database.remove(dataSource)
    }
}
