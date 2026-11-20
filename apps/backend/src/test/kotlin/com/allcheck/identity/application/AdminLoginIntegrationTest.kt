package com.allcheck.identity.application

import com.allcheck.global.exception.BusinessException
import com.allcheck.global.security.jwt.JwtTokenProvider
import com.allcheck.identity.AdminRole
import com.allcheck.identity.UserType
import com.allcheck.identity.application.login.AdminLoginService
import com.allcheck.identity.application.login.AdminSessionService
import com.allcheck.identity.domain.entity.account.Account
import com.allcheck.identity.domain.vo.account.AdminCredential
import com.allcheck.identity.exception.IdentityErrorCode
import com.allcheck.identity.infrastructure.login.password.AdminPasswordHasher
import com.allcheck.identity.infrastructure.repository.account.AccountRepository
import com.allcheck.identity.infrastructure.repository.session.AuthSessionRepository
import com.allcheck.identity.infrastructure.session.RefreshTokenProvider
import com.allcheck.identity.presentation.dto.response.session.TokenPairResponse
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import org.junit.jupiter.params.provider.ValueSource
import org.mockito.ArgumentMatchers.anyLong
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mockito
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.TestPropertySource
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.header
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionSynchronizationManager
import org.springframework.transaction.support.TransactionTemplate
import tools.jackson.databind.json.JsonMapper
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@SpringBootTest(
    properties = [
        "spring.datasource.url=\${IDENTITY_TEST_DATABASE_URL:jdbc:postgresql://localhost:15432/allcheck_identity_test}",
        "spring.datasource.username=\${IDENTITY_TEST_DATABASE_USER:allcheck}",
        "spring.datasource.password=\${IDENTITY_TEST_DATABASE_PASSWORD:allcheck_local_6}",
        "spring.jpa.show-sql=false",
        "identity.sessions.expiration=PT336H",
    ],
)
@AutoConfigureMockMvc
@TestPropertySource("classpath:jwt-test.properties")
class AdminLoginIntegrationTest @Autowired constructor(
    private val mvc: MockMvc,
    private val mapper: JsonMapper,
    private val login: AdminLoginService,
    private val adminSessions: AdminSessionService,
    private val accounts: AccountRepository,
    private val sessions: AuthSessionRepository,
    private val refreshTokens: RefreshTokenProvider,
    private val jdbc: JdbcTemplate,
    transactionManager: PlatformTransactionManager,
) {
    @MockitoSpyBean private lateinit var tokens: JwtTokenProvider

    @MockitoSpyBean private lateinit var passwords: AdminPasswordHasher

    private val tx = TransactionTemplate(transactionManager)
    private val ownAccounts = mutableListOf<Long>()
    private val endpoint = "/api/v1/auth/sessions/admin"
    private val password = "test-admin-password-!"

    @AfterEach
    fun removeOnlyOwnFixtures() {
        tx.executeWithoutResult {
            ownAccounts.forEach { id ->
                sessions.deleteAllByIdInBatch(sessions.findAllByAccountId(id).map { checkNotNull(it.id) })
                accounts.deleteById(id)
            }
        }
    }

    @ParameterizedTest
    @EnumSource(AdminRole::class)
    fun `두 관리자 역할은 사전 계정으로 로그인하고 독립 세션과 현재 역할을 반환한다`(role: AdminRole) {
        // given
        val admin = admin(role)

        // when
        val result = mvc.perform(
            post(endpoint).header(HttpHeaders.AUTHORIZATION, "Bearer malformed-access-token")
                .contentType(MediaType.APPLICATION_JSON).content(body(admin)),
        )
            .andExpect(status().isOk)
            .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
            .andExpect(header().string(HttpHeaders.PRAGMA, "no-cache"))
            .andExpect(header().doesNotExist(HttpHeaders.SET_COOKIE))
            .andExpect(jsonPath("$.user.userType").value("ADMIN"))
            .andExpect(jsonPath("$.user.role").value(role.name))
            .andExpect(jsonPath("$.password").doesNotExist())
            .andExpect(jsonPath("$.passwordHash").doesNotExist())
            .andExpect(jsonPath("$.tokens").doesNotExist())
            .andExpect(jsonPath("$.accountStatus").doesNotExist())
            .andReturn().response
        val response = mapper.readValue(result.contentAsString, TokenPairResponse::class.java)

        // then
        val stored = sessions.findAllByAccountId(checkNotNull(admin.id)).single()
        assertThat(response.user.accountId).isEqualTo(checkNotNull(admin.id))
        assertThat(response.tokenType).isEqualTo("Bearer")
        assertThat(response.expiresIn).isEqualTo(900)
        assertThat(response.refreshExpiresIn).isEqualTo(1209600)
        assertThat(stored.refreshTokenHash).isEqualTo(refreshTokens.hash(response.refreshToken))
        assertThat(stored.refreshTokenHash.value).isNotEqualTo(response.refreshToken)
        assertThat(Duration.between(stored.createdAt, stored.expiresAt)).isEqualTo(Duration.ofDays(14))
        assertThat(tokens.parseClaims(response.accessToken).subject).isEqualTo(checkNotNull(admin.id).toString())
        mvc.perform(get("/api/v1/auth/accounts/me").header(HttpHeaders.AUTHORIZATION, "Bearer ${response.accessToken}"))
            .andExpect(status().isOk).andExpect(jsonPath("$.role").value(role.name))
    }

    @Test
    fun `반복 로그인은 기존 세션을 유지하고 독립 Refresh Token을 발급한다`() {
        // given
        val admin = admin()
        val first = login.login(admin.adminCredential!!.loginId, password)

        // when
        val second = login.login(admin.adminCredential!!.loginId, password)

        // then
        assertThat(second.user.accountId).isEqualTo(first.user.accountId)
        assertThat(second.refreshToken).isNotEqualTo(first.refreshToken)
        assertThat(sessions.findAllByAccountId(checkNotNull(admin.id))).hasSize(2).allMatch { it.isActiveAt(Instant.now()) }
    }

    @ParameterizedTest
    @ValueSource(strings = ["unknown-id", "wrong-password", "case-changed-id", "disabled-wrong-password", "unsupported-hash"])
    fun `인증 실패 원인을 구분하지 않고 계정과 세션을 변경하지 않는다`(scenario: String) {
        // given
        val admin = admin(hash = if (scenario == "unsupported-hash") "{noop}$password" else passwords.encode(password))
        if (scenario == "disabled-wrong-password") tx.executeWithoutResult { accounts.findByIdForUpdate(checkNotNull(admin.id))!!.disable(Instant.now()) }
        val id = when (scenario) {
            "unknown-id" -> "missing-${UUID.randomUUID()}"
            "case-changed-id" -> admin.adminCredential!!.loginId.uppercase()
            else -> admin.adminCredential!!.loginId
        }
        val supplied = if (scenario in listOf("wrong-password", "disabled-wrong-password")) "incorrect-password" else password

        // when & then
        mvc.perform(post(endpoint).contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(mapOf("loginId" to id, "password" to supplied))))
            .andExpect(status().isUnauthorized).andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"))
            .andExpect(jsonPath("$.message").value("아이디 또는 비밀번호를 확인해 주세요."))
        assertThat(sessions.findAllByAccountId(checkNotNull(admin.id))).isEmpty()
        assertThat(accounts.findById(checkNotNull(admin.id)).orElseThrow().userType).isEqualTo(UserType.ADMIN)
        if (scenario == "unknown-id") assertThat(accounts.findByAdminCredentialLoginId(id)).isNull()
    }

    @Test
    fun `비활성 계정의 올바른 자격 증명은 403이며 계정을 재활성화하지 않는다`() {
        // given
        val admin = admin()
        tx.executeWithoutResult { accounts.findByIdForUpdate(checkNotNull(admin.id))!!.disable(Instant.now()) }

        // when & then
        mvc.perform(post(endpoint).contentType(MediaType.APPLICATION_JSON).content(body(admin)))
            .andExpect(status().isForbidden).andExpect(jsonPath("$.code").value("ACCOUNT_DISABLED"))
        assertThat(accounts.findById(checkNotNull(admin.id)).orElseThrow().enabled).isFalse()
        assertThat(sessions.findAllByAccountId(checkNotNull(admin.id))).isEmpty()
    }

    @ParameterizedTest
    @ValueSource(strings = ["{}", "{\"loginId\":null,\"password\":\"x\"}", "{\"loginId\":\"x\",\"password\":null}", "{\"loginId\":\" \",\"password\":\"x\"}", "{\"loginId\":\"x\",\"password\":\" \"}", "{\"loginId\":\"x\",\"password\":\"x\",\"role\":\"MASTER\"}", "{\"loginId\":\"x\",\"password\":\"x\",\"accountId\":\"another-account\"}", "{\"username\":\"x\",\"password\":\"x\"}"])
    fun `잘못된 입력과 클라이언트 역할 지정은 비밀번호 검증 전에 거부한다`(body: String) {
        // when & then
        mvc.perform(post(endpoint).contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isBadRequest)
        Mockito.verify(passwords, Mockito.never()).matches(anyString(), Mockito.nullable(String::class.java))
    }

    @ParameterizedTest
    @ValueSource(strings = ["loginId", "password"])
    fun `입력 길이 제한을 넘으면 비밀번호를 검증하지 않는다`(field: String) {
        // given
        val request = mapOf("loginId" to if (field == "loginId") "a".repeat(101) else "admin", "password" to if (field == "password") "x".repeat(201) else password)

        // when & then
        mvc.perform(post(endpoint).contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(request))).andExpect(status().isBadRequest)
        Mockito.verify(passwords, Mockito.never()).matches(anyString(), Mockito.nullable(String::class.java))
    }

    @Test
    fun `비밀번호 해시는 호출자 쓰기 트랜잭션을 중단하고 검증한다`() {
        // given
        val admin = admin()
        Mockito.doAnswer {
            check(!TransactionSynchronizationManager.isActualTransactionActive())
            it.callRealMethod()
        }.`when`(passwords).matches(anyString(), Mockito.nullable(String::class.java))

        // when
        val response = tx.execute { login.login(admin.adminCredential!!.loginId, password) }

        // then
        assertThat(response.user.accountId).isEqualTo(checkNotNull(admin.id))
    }

    @Test
    fun `서명 실패는 새 세션을 롤백하고 기존 사전 계정은 유지한다`() {
        // given
        val admin = admin()
        Mockito.doThrow(IllegalStateException("test-signing-failure")).`when`(tokens).createAccessToken(anyLong(), anyLong())

        // when & then
        mvc.perform(post(endpoint).contentType(MediaType.APPLICATION_JSON).content(body(admin)))
            .andExpect(status().isInternalServerError).andExpect(jsonPath("$.code").value("INTERNAL_SERVER_ERROR"))
        assertThat(accounts.existsById(checkNotNull(admin.id))).isTrue()
        assertThat(sessions.findAllByAccountId(checkNotNull(admin.id))).isEmpty()
    }

    @Test
    fun `자격 증명이 검증 이후 변경되면 이전 결과로 세션을 만들지 않는다`() {
        // given
        val admin = admin()
        val candidate = checkNotNull(adminSessions.candidate(admin.adminCredential!!.loginId))
        jdbc.update("update identity_accounts set password_hash = ?, version = version + 1 where account_id = ?", passwords.encode("replacement-password"), checkNotNull(admin.id))

        // when & then
        assertThatThrownBy { adminSessions.login(candidate) }.isInstanceOfSatisfying(BusinessException::class.java) {
            assertThat(it.errorCode).isEqualTo(IdentityErrorCode.INVALID_CREDENTIALS)
        }
        assertThat(sessions.findAllByAccountId(checkNotNull(admin.id))).isEmpty()
    }

    @Test
    fun `발급 직전 현재 관리자 역할을 반환하고 호출자 롤백도 따른다`() {
        // given
        val admin = admin()
        val candidate = checkNotNull(adminSessions.candidate(admin.adminCredential!!.loginId))
        jdbc.update("update identity_accounts set admin_role = 'SUB', version = version + 1 where account_id = ?", checkNotNull(admin.id))

        // when & then
        assertThatThrownBy {
            tx.executeWithoutResult {
                assertThat(adminSessions.login(candidate).user.role).isEqualTo(AdminRole.SUB)
                throw IllegalStateException("test-rollback")
            }
        }.isInstanceOf(IllegalStateException::class.java)
        assertThat(sessions.findAllByAccountId(checkNotNull(admin.id))).isEmpty()
    }

    @Test
    fun `동시 관리자 로그인은 계정을 중복 생성하거나 다른 세션을 폐기하지 않는다`() {
        // given
        val admin = admin()

        // when
        val responses = concurrently({ login.login(admin.adminCredential!!.loginId, password) }, { login.login(admin.adminCredential!!.loginId, password) })

        // then
        assertThat(responses.map { (it as TokenPairResponse).user.accountId }).containsOnly(checkNotNull(admin.id))
        assertThat(responses.map { (it as TokenPairResponse).refreshToken }).doesNotHaveDuplicates()
        assertThat(sessions.findAllByAccountId(checkNotNull(admin.id))).hasSize(2).allMatch { it.revokedAt == null }
    }

    @Test
    fun `비활성화와 로그인이 경쟁해도 종료 후 사용할 수 있는 세션이 남지 않는다`() {
        // given
        val admin = admin()

        // when
        concurrently(
            {
                try {
                    login.login(admin.adminCredential!!.loginId, password)
                } catch (ex: BusinessException) {
                    assertThat(ex.errorCode).isEqualTo(IdentityErrorCode.ACCOUNT_DISABLED)
                }
            },
            {
                tx.executeWithoutResult {
                    accounts.findByIdForUpdate(checkNotNull(admin.id))!!.disable(Instant.now())
                    sessions.findAllByAccountIdForUpdate(checkNotNull(admin.id)).forEach { it.revoke(Instant.now()) }
                }
            },
        )

        // then
        assertThat(accounts.findById(checkNotNull(admin.id)).orElseThrow().enabled).isFalse()
        assertThat(sessions.findAllByAccountId(checkNotNull(admin.id))).allMatch { it.revokedAt != null }
    }

    @Test
    fun `실제 관리자 로그인 토큰으로 갱신하고 로그아웃하면 기존 Access Token도 차단한다`() {
        // given
        val admin = admin()
        val first = login.login(admin.adminCredential!!.loginId, password)
        val original = sessions.findAllByAccountId(checkNotNull(admin.id)).single()

        // when
        val refreshed = mvc.perform(
            post("/api/v1/auth/tokens/refresh").contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(mapOf("refreshToken" to first.refreshToken))),
        )
            .andExpect(status().isOk).andReturn().response
        val replacement = mapper.readValue(refreshed.contentAsString, TokenPairResponse::class.java)
        assertThat(sessions.findAllByAccountId(checkNotNull(admin.id)).single().expiresAt).isEqualTo(original.expiresAt)
        assertThat(replacement.user.role).isEqualTo(AdminRole.MASTER)
        mvc.perform(
            post("/api/v1/auth/sessions/logout").contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(mapOf("refreshToken" to replacement.refreshToken))),
        )
            .andExpect(status().isNoContent)

        // then
        listOf(first.accessToken, replacement.accessToken).forEach { access ->
            mvc.perform(get("/api/v1/auth/accounts/me").header(HttpHeaders.AUTHORIZATION, "Bearer $access")).andExpect(status().isUnauthorized)
        }
    }

    @Test
    fun `생성 OpenAPI는 관리자 로그인을 공개하고 비밀번호를 응답용 필드로 노출하지 않는다`() {
        // when & then
        mvc.perform(get("/v3/api-docs")).andExpect(status().isOk)
            .andExpect(jsonPath("$.paths['$endpoint'].post.security").isEmpty())
            .andExpect(jsonPath("$.paths['$endpoint'].post.responses['401']").exists())
            .andExpect(jsonPath("$.components.schemas.AdminLoginRequest.properties.password.writeOnly").value(true))
        mvc.perform(get(endpoint)).andExpect(status().isUnauthorized)
        mvc.perform(post("/api/v1/auth/accounts/me").contentType(MediaType.APPLICATION_JSON).content("{}")).andExpect(status().isUnauthorized)
    }

    private fun admin(role: AdminRole = AdminRole.MASTER, hash: String = passwords.encode(password)): Account = tx.execute {
        accounts.saveAndFlush(Account.administrator(AdminCredential("admin-${UUID.randomUUID()}", hash, role), Instant.now())).also { ownAccounts.add(checkNotNull(it.id)) }
    }

    private fun body(admin: Account): String = mapper.writeValueAsString(mapOf("loginId" to admin.adminCredential!!.loginId, "password" to password))

    private fun concurrently(first: () -> Any, second: () -> Any): List<Any> {
        val ready = CountDownLatch(2)
        val executor = Executors.newFixedThreadPool(2)
        try {
            return listOf(first, second).map { action ->
                executor.submit<Any> {
                    ready.countDown()
                    check(ready.await(5, TimeUnit.SECONDS))
                    action()
                }
            }.map { it.get(20, TimeUnit.SECONDS) }
        } finally {
            executor.shutdownNow()
            check(executor.awaitTermination(10, TimeUnit.SECONDS))
        }
    }
}
