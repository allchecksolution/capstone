package com.allcheck.identity.application

import com.allcheck.global.exception.BusinessException
import com.allcheck.global.security.jwt.JwtTokenProvider
import com.allcheck.identity.AdminRole
import com.allcheck.identity.IdentityAccountOperations
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
import org.junit.jupiter.params.provider.EnumSource
import org.junit.jupiter.params.provider.ValueSource
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mockito
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.context.annotation.Primary
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.TestPropertySource
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.content
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.header
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import tools.jackson.databind.ObjectMapper
import java.sql.Timestamp
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.crypto.SecretKey

@SpringBootTest(
    properties = [
        "spring.datasource.url=\${IDENTITY_TEST_DATABASE_URL:jdbc:postgresql://localhost:15432/allcheck_identity_test}",
        "spring.datasource.username=\${IDENTITY_TEST_DATABASE_USER:allcheck}",
        "spring.datasource.password=\${IDENTITY_TEST_DATABASE_PASSWORD:allcheck_local_6}",
        "spring.jpa.show-sql=false",
    ],
)
@AutoConfigureMockMvc
@TestPropertySource("classpath:jwt-test.properties")
@Import(SessionLifecycleIntegrationTest.TestConfig::class)
class SessionLifecycleIntegrationTest @Autowired constructor(
    private val mockMvc: MockMvc,
    private val mapper: ObjectMapper,
    private val lifecycle: SessionLifecycleService,
    private val accountLifecycle: IdentityAccountOperations,
    private val accounts: AccountRepository,
    private val sessions: AuthSessionRepository,
    private val refreshTokens: RefreshTokenProvider,
    private val clock: Clock,
    private val secretKey: SecretKey,
    private val jdbc: JdbcTemplate,
    transactionManager: PlatformTransactionManager,
) {
    @MockitoSpyBean
    private lateinit var accessTokens: JwtTokenProvider

    private val transactions = TransactionTemplate(transactionManager)
    private val now = clock.instant()
    private val ownAccounts = mutableListOf<String>()
    private val refreshPath = "/api/v1/auth/tokens/refresh"
    private val logoutPath = "/api/v1/auth/sessions/logout"
    private val mePath = "/api/v1/auth/accounts/me"

    @AfterEach
    fun removeOwnFixtures() {
        transactions.executeWithoutResult {
            ownAccounts.forEach { accountId ->
                jdbc.update("delete from request_limit_buckets where bucket_id = ?", "token-refresh:$accountId")
                sessions.deleteAllByIdInBatch(sessions.findAllByAccountId(accountId).map { it.id })
                accounts.deleteById(accountId)
            }
        }
    }

    @Test
    fun `계정의 모든 기기 갱신을 합산해 31회부터 차단하고 만료 후 같은 토큰을 다시 사용할 수 있다`() {
        // given
        val first = fixture()
        val second = fixture(accountId = first.accountId)
        val unrelated = fixture()
        val current = mutableListOf(first.refreshToken, second.refreshToken)
        repeat(30) { index ->
            val device = index % 2
            val response = mockMvc.perform(post(refreshPath).contentType(MediaType.APPLICATION_JSON).content(body(current[device])))
                .andExpect(status().isOk).andReturn().response
            current[device] = mapper.readValue(response.contentAsString, TokenPairResponse::class.java).refreshToken
        }
        val bucket = "token-refresh:${first.accountId}"
        val endsAt = jdbc.queryForObject("select window_ends_at from request_limit_buckets where bucket_id = ?", Timestamp::class.java, bucket)

        // when & then
        repeat(2) {
            val response = mockMvc.perform(post(refreshPath).contentType(MediaType.APPLICATION_JSON).content(body(current[0])))
                .andExpect(status().isTooManyRequests)
                .andExpect(jsonPath("$.code").value("TOO_MANY_REQUESTS"))
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andReturn().response
            assertThat(response.getHeader(HttpHeaders.RETRY_AFTER)?.toLong()).isBetween(1L, 60L)
        }
        assertThat(jdbc.queryForObject("select window_ends_at from request_limit_buckets where bucket_id = ?", Timestamp::class.java, bucket)).isEqualTo(endsAt)
        assertThat(sessions.findById(first.sessionId).orElseThrow().refreshTokenHash).isEqualTo(refreshTokens.hash(current[0]))
        expectAccessible(first.accessToken)
        expectAccessible(second.accessToken)
        expectRefreshRejected("unknown-refresh-token")
        mockMvc.perform(post(refreshPath).contentType(MediaType.APPLICATION_JSON).content(body(unrelated.refreshToken))).andExpect(status().isOk)
        // 로그아웃은 갱신 한도에 영향받지 않는다.
        mockMvc.perform(post(logoutPath).contentType(MediaType.APPLICATION_JSON).content(body(current[1]))).andExpect(status().isNoContent)

        jdbc.update("update request_limit_buckets set window_ends_at = statement_timestamp() - interval '1 second' where bucket_id = ?", bucket)
        mockMvc.perform(post(refreshPath).contentType(MediaType.APPLICATION_JSON).content(body(current[0]))).andExpect(status().isOk)
    }

    @ParameterizedTest
    @EnumSource(AdminRole::class)
    fun `MASTER와 SUB도 동일한 계정별 갱신 한도를 적용한다`(role: AdminRole) {
        // given
        val account = saveAccount(Account.administrator(AdminCredential("rate-admin", "test-password-hash", role), now.minus(Duration.ofDays(20))))
        val fixture = fixture(accountId = account.id)
        var token = fixture.refreshToken
        repeat(30) {
            val response = mockMvc.perform(post(refreshPath).contentType(MediaType.APPLICATION_JSON).content(body(token)))
                .andExpect(status().isOk).andExpect(jsonPath("$.user.role").value(role.name)).andReturn().response
            token = mapper.readValue(response.contentAsString, TokenPairResponse::class.java).refreshToken
        }

        // when & then
        mockMvc.perform(post(refreshPath).contentType(MediaType.APPLICATION_JSON).content(body(token)))
            .andExpect(status().isTooManyRequests).andExpect(jsonPath("$.code").value("TOO_MANY_REQUESTS"))
        expectAccessible(fixture.accessToken)
    }

    @Test
    fun `갱신은 같은 세션에서 토큰을 교체하고 절대 만료까지 남은 초를 반환한다`() {
        // given
        val fixture = fixture()

        // when
        val response = mockMvc.perform(post(refreshPath).contentType(MediaType.APPLICATION_JSON).content(body(fixture.refreshToken)))
            .andExpect(status().isOk)
            .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
            .andExpect(header().string(HttpHeaders.PRAGMA, "no-cache"))
            .andExpect(header().doesNotExist(HttpHeaders.SET_COOKIE))
            .andReturn()
        val pair = mapper.readValue(response.response.contentAsString, TokenPairResponse::class.java)

        // then
        assertThat(pair.refreshToken).isNotEqualTo(fixture.refreshToken)
        assertThat(pair.user.accountId).isEqualTo(fixture.accountId)
        assertThat(pair.user.userType.name).isEqualTo("DRIVER")
        assertThat(pair.user.role).isNull()
        assertThat(pair.tokenType).isEqualTo("Bearer")
        assertThat(pair.expiresIn).isEqualTo(900)
        assertThat(pair.refreshExpiresIn).isEqualTo(Duration.ofDays(13).seconds)
        assertThat(mapper.readTree(response.response.contentAsString).size()).isEqualTo(6)
        assertThat(response.request.getSession(false)).isNull()
        val stored = sessions.findById(fixture.sessionId).orElseThrow()
        assertThat(stored.expiresAt).isEqualTo(fixture.expiresAt)
        assertThat(stored.lastRefreshedAt).isEqualTo(now)
        assertThat(stored.refreshTokenHash).isEqualTo(refreshTokens.hash(pair.refreshToken))
        assertThat(stored.refreshTokenHash.value).isNotEqualTo(pair.refreshToken)
        assertThat(sessions.findByRefreshTokenHash(refreshTokens.hash(fixture.refreshToken))).isNull()
        assertThat(sessions.findAllByAccountId(fixture.accountId)).hasSize(1)
        assertThat(accessTokens.parseClaims(pair.accessToken).get("sid", String::class.java)).isEqualTo(fixture.sessionId)
        expectAccessible(pair.accessToken)
        expectAccessible(fixture.accessToken)
    }

    @ParameterizedTest
    @EnumSource(AdminRole::class)
    fun `관리자 갱신은 현재 DB의 사용자 유형과 역할을 보존한다`(role: AdminRole) {
        // given
        val account = saveAccount(Account.administrator(AdminCredential("stage6-admin", "test-password-hash", role), now.minus(Duration.ofDays(20))))
        val fixture = fixture(accountId = account.id)

        // when & then
        mockMvc.perform(post(refreshPath).contentType(MediaType.APPLICATION_JSON).content(body(fixture.refreshToken)))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.user.userType").value("ADMIN"))
            .andExpect(jsonPath("$.user.role").value(role.name))
    }

    @Test
    fun `이전 토큰 재사용은 새 토큰과 다른 기기 세션을 폐기하지 않는다`() {
        // given
        val first = fixture()
        val other = fixture(accountId = first.accountId)
        val replacement = lifecycle.refresh(first.refreshToken)

        // when & then
        expectRefreshRejected(first.refreshToken)
        val next = lifecycle.refresh(replacement.refreshToken)
        expectAccessible(next.accessToken)
        expectAccessible(other.accessToken)
        assertThat(sessions.findById(first.sessionId).orElseThrow().revokedAt).isNull()
        assertThat(sessions.findById(other.sessionId).orElseThrow().revokedAt).isNull()
    }

    @ParameterizedTest
    @ValueSource(strings = ["unknown", "expired", "expires-now", "revoked", "future"])
    fun `사용할 수 없는 Refresh Token은 갱신을 거부하고 세션을 변경하지 않는다`(scenario: String) {
        // given
        val fixture = unusableFixture(scenario)
        val before = sessions.findById(fixture.sessionId).orElseThrow()
        val token = if (scenario == "unknown") "unknown-refresh-token" else fixture.refreshToken

        // when & then
        expectRefreshRejected(token)
        val after = sessions.findById(fixture.sessionId).orElseThrow()
        assertThat(after.refreshTokenHash).isEqualTo(before.refreshTokenHash)
        assertThat(after.revokedAt).isEqualTo(before.revokedAt)
        assertThat(after.version).isEqualTo(before.version)
    }

    @Test
    fun `비활성 계정의 유효한 Refresh Token은 403이고 토큰을 소비하지 않는다`() {
        // given
        val fixture = fixture()
        transactions.executeWithoutResult { accounts.findById(fixture.accountId).orElseThrow().disable(now) }

        // when & then
        mockMvc.perform(post(refreshPath).contentType(MediaType.APPLICATION_JSON).content(body(fixture.refreshToken)))
            .andExpect(status().isForbidden)
            .andExpect(jsonPath("$.code").value("ACCOUNT_DISABLED"))
        assertThat(sessions.findById(fixture.sessionId).orElseThrow().refreshTokenHash).isEqualTo(refreshTokens.hash(fixture.refreshToken))
    }

    @Test
    fun `세션 만료 직전 갱신도 절대 만료를 연장하지 않는다`() {
        // given
        val fixture = fixture(expiresAt = now.plusSeconds(30))

        // when
        val pair = lifecycle.refresh(fixture.refreshToken)

        // then
        assertThat(pair.refreshExpiresIn).isEqualTo(30)
        assertThat(pair.expiresIn).isEqualTo(900)
        assertThat(sessions.findById(fixture.sessionId).orElseThrow().expiresAt).isEqualTo(now.plusSeconds(30))
    }

    @Test
    fun `JWT 발급 실패는 이미 flush한 Refresh Token 회전도 롤백한다`() {
        // given
        val fixture = fixture()
        val before = sessions.findById(fixture.sessionId).orElseThrow()
        Mockito.doThrow(IllegalStateException("test-signing-failure"))
            .`when`(accessTokens).createAccessToken(anyString(), anyString())

        // when
        mockMvc.perform(post(refreshPath).contentType(MediaType.APPLICATION_JSON).content(body(fixture.refreshToken)))
            .andExpect(status().isInternalServerError)
            .andExpect(jsonPath("$.code").value("INTERNAL_SERVER_ERROR"))

        // then
        val stored = sessions.findById(fixture.sessionId).orElseThrow()
        assertThat(stored.refreshTokenHash).isEqualTo(before.refreshTokenHash)
        assertThat(stored.lastRefreshedAt).isNull()
        assertThat(stored.version).isEqualTo(before.version)
        assertThat(jdbc.queryForObject("select request_count from request_limit_buckets where bucket_id = ?", Int::class.java, "token-refresh:${fixture.accountId}")).isEqualTo(1)
        Mockito.reset(accessTokens)
        expectAccessible(lifecycle.refresh(fixture.refreshToken).accessToken)
    }

    @Test
    fun `로그아웃은 해당 세션의 기존 Access Token과 갱신을 차단하고 다른 기기를 유지한다`() {
        // given
        val fixture = fixture()
        val other = fixture(accountId = fixture.accountId)
        expectAccessible(fixture.accessToken)

        // when
        mockMvc.perform(post(logoutPath).contentType(MediaType.APPLICATION_JSON).content(body(fixture.refreshToken)))
            .andExpect(status().isNoContent)
            .andExpect(content().string(""))
            .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
            .andExpect(header().doesNotExist(HttpHeaders.SET_COOKIE))

        // then
        assertThat(sessions.findById(fixture.sessionId).orElseThrow().revokedAt).isEqualTo(now)
        mockMvc.perform(get(mePath).header(HttpHeaders.AUTHORIZATION, "Bearer ${fixture.accessToken}"))
            .andExpect(status().isUnauthorized)
            .andExpect(jsonPath("$.code").value("INVALID_TOKEN"))
        expectRefreshRejected(fixture.refreshToken)
        expectAccessible(other.accessToken)
        expectAccessible(lifecycle.refresh(other.refreshToken).accessToken)
    }

    @Test
    fun `갱신 후 로그아웃은 세션에서 발급한 이전 토큰과 새 Access Token을 모두 차단한다`() {
        // given
        val fixture = fixture()
        val previousAccessToken = JwtTokenProvider(secretKey, Duration.ofMinutes(15), Clock.offset(clock, Duration.ofMinutes(-1)))
            .createAccessToken(fixture.accountId, fixture.sessionId)
        val pair = lifecycle.refresh(fixture.refreshToken)
        expectAccessible(previousAccessToken)
        expectAccessible(pair.accessToken)

        // when
        mockMvc.perform(post(logoutPath).contentType(MediaType.APPLICATION_JSON).content(body(pair.refreshToken)))
            .andExpect(status().isNoContent)

        // then
        listOf(previousAccessToken, pair.accessToken).forEach { token ->
            mockMvc.perform(get(mePath).header(HttpHeaders.AUTHORIZATION, "Bearer $token"))
                .andExpect(status().isUnauthorized)
                .andExpect(jsonPath("$.code").value("INVALID_TOKEN"))
        }
        expectRefreshRejected(pair.refreshToken)
    }

    @Test
    fun `계정이 비활성이어도 유효한 Refresh Token의 로그아웃을 허용한다`() {
        // given
        val fixture = fixture()
        transactions.executeWithoutResult { accounts.findById(fixture.accountId).orElseThrow().disable(now) }

        // when & then
        mockMvc.perform(post(logoutPath).contentType(MediaType.APPLICATION_JSON).content(body(fixture.refreshToken)))
            .andExpect(status().isNoContent)
        assertThat(sessions.findById(fixture.sessionId).orElseThrow().revokedAt).isEqualTo(now)
    }

    @ParameterizedTest
    @ValueSource(strings = ["unknown", "expired", "expires-now", "revoked", "future", "consumed"])
    fun `무효 만료 폐기 소비된 토큰의 로그아웃은 204이고 다른 상태를 바꾸지 않는다`(scenario: String) {
        // given
        val fixture = unusableFixture(scenario)
        val other = fixture(accountId = fixture.accountId)
        val replacement = if (scenario == "consumed") lifecycle.refresh(fixture.refreshToken) else null
        val before = sessions.findById(fixture.sessionId).orElseThrow()
        val token = if (scenario == "unknown") "unknown-refresh-token" else fixture.refreshToken

        // when & then
        mockMvc.perform(post(logoutPath).contentType(MediaType.APPLICATION_JSON).content(body(token)))
            .andExpect(status().isNoContent)
            .andExpect(content().string(""))
        val after = sessions.findById(fixture.sessionId).orElseThrow()
        assertThat(after.refreshTokenHash).isEqualTo(before.refreshTokenHash)
        assertThat(after.revokedAt).isEqualTo(before.revokedAt)
        assertThat(after.version).isEqualTo(before.version)
        expectAccessible(other.accessToken)
        if (replacement != null) expectAccessible(lifecycle.refresh(replacement.refreshToken).accessToken)
    }

    @Test
    fun `로그아웃 트랜잭션이 롤백되면 기존 토큰을 계속 사용할 수 있다`() {
        // given
        val fixture = fixture()

        // when
        assertThatThrownBy {
            transactions.executeWithoutResult {
                lifecycle.logout(fixture.refreshToken)
                sessions.flush()
                throw IllegalStateException("test-rollback")
            }
        }.isInstanceOf(IllegalStateException::class.java)

        // then
        assertThat(sessions.findById(fixture.sessionId).orElseThrow().revokedAt).isNull()
        expectAccessible(fixture.accessToken)
        expectAccessible(lifecycle.refresh(fixture.refreshToken).accessToken)
    }

    @ParameterizedTest
    @ValueSource(strings = ["refresh", "logout"])
    fun `만료된 Authorization 헤더가 있어도 본문의 Refresh Token으로 처리한다`(operation: String) {
        // given
        val fixture = fixture()
        val expired = JwtTokenProvider(
            secretKey,
            Duration.ofMinutes(15),
            Clock.offset(clock, Duration.ofHours(-1)),
        ).createAccessToken(fixture.accountId, fixture.sessionId)
        val path = if (operation == "refresh") refreshPath else logoutPath

        // when & then
        mockMvc.perform(
            post(path).header(HttpHeaders.AUTHORIZATION, "Bearer $expired")
                .contentType(MediaType.APPLICATION_JSON).content(body(fixture.refreshToken)),
        ).andExpect(status().`is`(if (operation == "refresh") 200 else 204))
    }

    @ParameterizedTest
    @ValueSource(strings = ["{}", "{\"refreshToken\":null}", "{\"refreshToken\":\"\"}", "{\"refreshToken\":\"token with spaces\"}", "{\"refreshToken\":\"token\\nvalue\"}", "{\"refreshToken\":\"token\",\"accountId\":\"other\"}", "{\"refreshToken\":\"token\",\"role\":\"MASTER\"}"])
    fun `갱신과 로그아웃의 잘못된 입력은 공통 400이고 토큰을 소비하지 않는다`(requestBody: String) {
        // given
        val fixture = fixture()

        // when & then
        listOf(refreshPath, logoutPath).forEach { path ->
            mockMvc.perform(post(path).contentType(MediaType.APPLICATION_JSON).content(requestBody))
                .andExpect(status().isBadRequest)
                .andExpect(jsonPath("$.code").value("INVALID_INPUT_VALUE"))
        }
        assertThat(sessions.findById(fixture.sessionId).orElseThrow().refreshTokenHash).isEqualTo(refreshTokens.hash(fixture.refreshToken))
    }

    @Test
    fun `길이 제한을 초과한 Refresh Token은 갱신과 로그아웃에서 거부한다`() {
        // when & then
        listOf(refreshPath, logoutPath).forEach { path ->
            mockMvc.perform(post(path).contentType(MediaType.APPLICATION_JSON).content(body("a".repeat(4001))))
                .andExpect(status().isBadRequest)
        }
    }

    @Test
    fun `동일 토큰의 동시 갱신은 하나만 커밋하고 패자는 교체 토큰을 폐기하지 않는다`() {
        // given
        val fixture = fixture()

        // when
        val results = concurrently(
            { refreshOutcome(fixture.refreshToken) },
            { refreshOutcome(fixture.refreshToken) },
        )

        // then
        assertThat(results.count { it is TokenPairResponse }).isEqualTo(1)
        assertThat(results.count { it == IdentityErrorCode.REFRESH_TOKEN_INVALID }).isEqualTo(1)
        val winner = results.filterIsInstance<TokenPairResponse>().single()
        assertThat(sessions.findById(fixture.sessionId).orElseThrow().refreshTokenHash).isEqualTo(refreshTokens.hash(winner.refreshToken))
        expectAccessible(lifecycle.refresh(winner.refreshToken).accessToken)
        assertThat(sessions.findAllByAccountId(fixture.accountId)).hasSize(1)
    }

    @Test
    fun `동시 로그아웃은 204로 끝나며 다른 기기 세션을 유지한다`() {
        // given
        val fixture = fixture()
        val other = fixture(accountId = fixture.accountId)

        // when
        val results = concurrently(
            { mockMvc.perform(post(logoutPath).contentType(MediaType.APPLICATION_JSON).content(body(fixture.refreshToken))).andReturn().response.status },
            { mockMvc.perform(post(logoutPath).contentType(MediaType.APPLICATION_JSON).content(body(fixture.refreshToken))).andReturn().response.status },
        )

        // then
        assertThat(results).containsExactly(204, 204)
        assertThat(sessions.findById(fixture.sessionId).orElseThrow().revokedAt).isEqualTo(now)
        expectAccessible(other.accessToken)
    }

    @Test
    fun `갱신과 이전 토큰 로그아웃의 경쟁은 두 결과 중 하나로 일관되게 커밋한다`() {
        // given
        val fixture = fixture()
        val other = fixture(accountId = fixture.accountId)

        // when
        val results = concurrently(
            { refreshOutcome(fixture.refreshToken) },
            {
                lifecycle.logout(fixture.refreshToken)
                "logged-out"
            },
        )

        // then
        val stored = sessions.findById(fixture.sessionId).orElseThrow()
        val refresh = results.first()
        if (refresh is TokenPairResponse) {
            assertThat(stored.revokedAt).isNull()
            assertThat(stored.refreshTokenHash).isEqualTo(refreshTokens.hash(refresh.refreshToken))
            expectAccessible(lifecycle.refresh(refresh.refreshToken).accessToken)
        } else {
            assertThat(refresh).isEqualTo(IdentityErrorCode.REFRESH_TOKEN_INVALID)
            assertThat(stored.revokedAt).isEqualTo(now)
        }
        expectAccessible(other.accessToken)
    }

    @Test
    fun `갱신과 계정 비활성화의 경쟁 후에는 해당 계정의 Access Token을 사용할 수 없다`() {
        // given
        val fixture = fixture()

        // when
        val results = concurrently(
            { refreshOutcome(fixture.refreshToken) },
            {
                accountLifecycle.deactivateDriverAccount(fixture.accountId)
                "disabled"
            },
        )

        // then
        assertThat(accounts.findById(fixture.accountId).orElseThrow().enabled).isFalse()
        mockMvc.perform(get(mePath).header(HttpHeaders.AUTHORIZATION, "Bearer ${fixture.accessToken}"))
            .andExpect(status().isUnauthorized)
        val refresh = results.first()
        if (refresh is TokenPairResponse) {
            mockMvc.perform(get(mePath).header(HttpHeaders.AUTHORIZATION, "Bearer ${refresh.accessToken}"))
                .andExpect(status().isUnauthorized)
            expectRefreshRejected(refresh.refreshToken)
        } else {
            assertThat(refresh).isIn(IdentityErrorCode.ACCOUNT_DISABLED, IdentityErrorCode.REFRESH_TOKEN_INVALID)
        }
    }

    @Test
    fun `생성 OpenAPI는 갱신과 로그아웃에 Bearer 인증을 요구하지 않는다`() {
        // when & then
        mockMvc.perform(get("/v3/api-docs"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.paths['$refreshPath'].post.security").isEmpty())
            .andExpect(jsonPath("$.paths['$logoutPath'].post.security").isEmpty())
            .andExpect(jsonPath("$.paths['$refreshPath'].post.responses['200'].content['application/json'].schema['\$ref']").value("#/components/schemas/TokenPairResponse"))
            .andExpect(jsonPath("$.paths['$refreshPath'].post.responses['429'].headers['Retry-After'].schema.minimum").value(1))
            .andExpect(jsonPath("$.paths['$logoutPath'].post.responses['204'].content").doesNotExist())
            .andExpect(jsonPath("$.paths['$logoutPath'].post.responses['429']").doesNotExist())
            .andExpect(jsonPath("$.paths['$refreshPath'].get").doesNotExist())
    }

    @ParameterizedTest
    @ValueSource(strings = ["/api/v1/auth/sessions/admin/other", "/api/v1/auth/sessions/other", "/api/v1/auth/tokens/other"])
    fun `정의되지 않은 인증 경로와 다른 HTTP 메서드를 함께 공개하지 않는다`(path: String) {
        // when & then
        mockMvc.perform(post(path).contentType(MediaType.APPLICATION_JSON).content("{}"))
            .andExpect(status().isUnauthorized)
        mockMvc.perform(get(refreshPath)).andExpect(status().isUnauthorized)
        mockMvc.perform(get(logoutPath)).andExpect(status().isUnauthorized)
    }

    private fun saveAccount(account: Account): Account {
        val saved = transactions.execute { accounts.saveAndFlush(account) }
        ownAccounts.add(saved.id)
        return saved
    }

    private fun fixture(
        accountId: String = saveAccount(Account.driver(SocialLogin.kakao(600000001L + ownAccounts.size), now.minus(Duration.ofDays(20)))).id,
        createdAt: Instant = now.minus(Duration.ofDays(1)),
        expiresAt: Instant = createdAt.plus(Duration.ofDays(14)),
    ): SessionFixture {
        val refreshToken = refreshTokens.generate()
        val session = transactions.execute {
            sessions.saveAndFlush(AuthSession(accountId, refreshTokens.hash(refreshToken), createdAt, expiresAt))
        }
        return SessionFixture(accountId, session.id, refreshToken, accessTokens.createAccessToken(accountId, session.id), expiresAt)
    }

    private fun unusableFixture(scenario: String): SessionFixture {
        val fixture = when (scenario) {
            "expired" -> fixture(createdAt = now.minus(Duration.ofDays(15)), expiresAt = now.minusSeconds(1))
            "expires-now" -> fixture(expiresAt = now)
            "future" -> fixture(createdAt = now.plusSeconds(1), expiresAt = now.plusSeconds(60))
            else -> fixture()
        }
        if (scenario == "revoked") transactions.executeWithoutResult { sessions.findById(fixture.sessionId).orElseThrow().revoke(now) }
        return fixture
    }

    private fun body(token: String): String = """{"refreshToken":"$token"}"""

    private fun expectAccessible(token: String) {
        mockMvc.perform(get(mePath).header(HttpHeaders.AUTHORIZATION, "Bearer $token")).andExpect(status().isOk)
    }

    private fun expectRefreshRejected(token: String) {
        mockMvc.perform(post(refreshPath).contentType(MediaType.APPLICATION_JSON).content(body(token)))
            .andExpect(status().isUnauthorized)
            .andExpect(jsonPath("$.code").value("REFRESH_TOKEN_INVALID"))
    }

    private fun refreshOutcome(token: String): Any = try {
        lifecycle.refresh(token)
    } catch (ex: BusinessException) {
        ex.errorCode
    }

    private fun concurrently(first: () -> Any, second: () -> Any): List<Any> {
        val barrier = CountDownLatch(2)
        val executor = Executors.newFixedThreadPool(2)
        try {
            val futures = listOf(first, second).map { action ->
                executor.submit<Any> {
                    barrier.countDown()
                    check(barrier.await(5, TimeUnit.SECONDS))
                    action()
                }
            }
            return futures.map { it.get(15, TimeUnit.SECONDS) }
        } finally {
            executor.shutdownNow()
            check(executor.awaitTermination(10, TimeUnit.SECONDS))
        }
    }

    private class SessionFixture(
        val accountId: String,
        val sessionId: String,
        val refreshToken: String,
        val accessToken: String,
        val expiresAt: Instant,
    )

    @TestConfiguration(proxyBeanMethods = false)
    class TestConfig {
        @Bean
        @Primary
        fun sessionLifecycleClock(): Clock = Clock.fixed(Instant.parse("2026-11-17T12:00:00Z"), ZoneOffset.UTC)
    }
}
