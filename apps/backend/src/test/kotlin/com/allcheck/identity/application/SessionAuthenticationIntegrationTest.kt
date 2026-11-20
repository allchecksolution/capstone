package com.allcheck.identity.application

import com.allcheck.global.security.jwt.JwtTokenProvider
import com.allcheck.global.security.principal.AuthPrincipal
import com.allcheck.identity.AdminRole
import com.allcheck.identity.domain.entity.account.Account
import com.allcheck.identity.domain.entity.session.AuthSession
import com.allcheck.identity.domain.vo.account.AdminCredential
import com.allcheck.identity.domain.vo.account.SocialLogin
import com.allcheck.identity.infrastructure.login.kakao.KakaoClient
import com.allcheck.identity.infrastructure.repository.account.AccountRepository
import com.allcheck.identity.infrastructure.repository.session.AuthSessionRepository
import com.allcheck.identity.infrastructure.session.RefreshTokenProvider
import com.allcheck.identity.presentation.dto.response.session.TokenPairResponse
import io.jsonwebtoken.Jwts
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import org.junit.jupiter.params.provider.ValueSource
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
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.security.core.Authentication
import org.springframework.test.context.TestPropertySource
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.content
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.header
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController
import tools.jackson.databind.ObjectMapper
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.Base64
import java.util.Date
import javax.crypto.SecretKey
import javax.crypto.spec.SecretKeySpec

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
@Import(SessionAuthenticationIntegrationTest.TestConfig::class, SessionAuthenticationIntegrationTest.TestController::class)
class SessionAuthenticationIntegrationTest @Autowired constructor(
    private val mockMvc: MockMvc,
    private val mapper: ObjectMapper,
    private val tokens: JwtTokenProvider,
    private val secretKey: SecretKey,
    private val accounts: AccountRepository,
    private val sessions: AuthSessionRepository,
    private val refreshTokens: RefreshTokenProvider,
    private val clock: Clock,
    transactionManager: PlatformTransactionManager,
) {
    @MockitoBean
    private lateinit var kakaoClient: KakaoClient

    private val transactions = TransactionTemplate(transactionManager)
    private val ownAccounts = mutableListOf<String>()
    private val now: Instant = clock.instant()
    private val endpoint = "/api/v1/auth/accounts/me"
    private val socialLogin = SocialLogin.kakao(500000001)

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
    fun `카카오 로그인 토큰으로 내 인증 계정만 조회하고 세션 쿠키를 만들지 않는다`() {
        // given
        Mockito.`when`(kakaoClient.verify("sdk-token")).thenReturn(socialLogin)
        val login = mockMvc.perform(
            post("/api/v1/auth/sessions/kakao")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"kakaoAccessToken":"sdk-token"}"""),
        ).andExpect(status().isOk).andReturn().response
        val body = mapper.readValue(login.contentAsString, TokenPairResponse::class.java)
        val accountId = body.user.accountId
        ownAccounts.add(accountId)

        // when
        val request = mockMvc.perform(
            get(endpoint).header(HttpHeaders.AUTHORIZATION, "Bearer ${body.accessToken}"),
        )

        // then
        val result = request.andExpect(status().isOk)
            .andExpect(content().json("""{"accountId":"$accountId","userType":"DRIVER","role":null}"""))
            .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
            .andExpect(header().doesNotExist(HttpHeaders.SET_COOKIE))
            .andReturn()
        assertThat(mapper.readTree(result.response.contentAsString).size()).isEqualTo(3)
        assertThat(result.request.getSession(false)).isNull()
    }

    @ParameterizedTest
    @EnumSource(AdminRole::class)
    fun `관리자는 저장된 역할을 반환하고 미확정 업무 권한을 얻지 않는다`(role: AdminRole) {
        // given
        val account = saveAccount(Account.administrator(AdminCredential("stage5-admin", "test-password-hash", role), now))
        val session = saveSession(account)
        val token = signedClaims(account.id, session.id, mapOf("role" to "MASTER", "authorities" to listOf("TEST_ACCESS")))

        // when & then
        mockMvc.perform(get(endpoint).header(HttpHeaders.AUTHORIZATION, "Bearer $token"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.userType").value("ADMIN"))
            .andExpect(jsonPath("$.role").value(role.name))
        mockMvc.perform(get("/api/v1/session-tests/principal").header(HttpHeaders.AUTHORIZATION, "Bearer $token"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.accountId").value(account.id))
            .andExpect(jsonPath("$.name").value(account.id))
            .andExpect(jsonPath("$.sessionId").value(session.id))
            .andExpect(jsonPath("$.userType").value("ADMIN"))
            .andExpect(jsonPath("$.role").value(role.name))
            .andExpect(jsonPath("$.authorities[0]").value("ROLE_ADMIN"))
        mockMvc.perform(get("/api/v1/session-tests/restricted").header(HttpHeaders.AUTHORIZATION, "Bearer $token"))
            .andExpect(status().isForbidden)
            .andExpect(jsonPath("$.code").value("ACCESS_DENIED"))
    }

    @Test
    fun `JWT의 과거 사용자 유형과 역할보다 현재 DB 인증 정보를 사용한다`() {
        // given
        val account = saveDriver()
        val session = saveSession(account)
        val token = signedClaims(account.id, session.id, mapOf("userType" to "ADMIN", "role" to "MASTER"))

        // when & then
        mockMvc.perform(get(endpoint).header(HttpHeaders.AUTHORIZATION, "bearer $token"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.userType").value("DRIVER"))
            .andExpect(jsonPath("$.role").value(org.hamcrest.Matchers.nullValue()))
    }

    @Test
    fun `토큰을 발급한 뒤 계정을 비활성화하면 다음 요청부터 모든 세션을 차단한다`() {
        // given
        val account = saveDriver()
        val token = tokens.createAccessToken(account.id, saveSession(account).id)
        val otherToken = tokens.createAccessToken(account.id, saveSession(account).id)
        mockMvc.perform(get(endpoint).header(HttpHeaders.AUTHORIZATION, "Bearer $token")).andExpect(status().isOk)

        // when
        transactions.executeWithoutResult { accounts.findById(account.id).orElseThrow().disable(now) }

        // then
        expectInvalidToken(token)
        expectInvalidToken(otherToken)
    }

    @Test
    fun `세션 폐기는 이미 발급된 Access Token을 차단하고 다른 기기 세션은 유지한다`() {
        // given
        val account = saveDriver()
        val session = saveSession(account)
        val token = tokens.createAccessToken(account.id, session.id)
        val otherToken = tokens.createAccessToken(account.id, saveSession(account).id)
        mockMvc.perform(get(endpoint).header(HttpHeaders.AUTHORIZATION, "Bearer $token")).andExpect(status().isOk)

        // when
        transactions.executeWithoutResult { sessions.findById(session.id).orElseThrow().revoke(now) }

        // then
        expectInvalidToken(token)
        mockMvc.perform(get(endpoint).header(HttpHeaders.AUTHORIZATION, "Bearer $otherToken")).andExpect(status().isOk)
        mockMvc.perform(get(endpoint)).andExpect(status().isUnauthorized)
    }

    @ParameterizedTest
    @ValueSource(strings = ["expired", "expires-now", "future", "unknown-session", "unknown-account", "mismatch"])
    fun `서명된 JWT도 사용할 수 없는 세션이나 계정 연결이면 거부한다`(scenario: String) {
        // given
        val account = saveDriver()
        val session = when (scenario) {
            "expired" -> saveSession(account, now.minusSeconds(120), now.minusSeconds(1))
            "expires-now" -> saveSession(account, now.minusSeconds(120), now)
            "future" -> saveSession(account, now.plusSeconds(60), now.plusSeconds(120))
            else -> saveSession(account)
        }
        val accountId = when (scenario) {
            "unknown-account" -> "missing-account"
            "mismatch" -> saveAccount(Account.driver(SocialLogin.kakao(500000002), now)).id
            else -> account.id
        }
        val sessionId = if (scenario == "unknown-session") "missing-session" else session.id

        // when & then
        expectInvalidToken(tokens.createAccessToken(accountId, sessionId))
    }

    @ParameterizedTest
    @ValueSource(strings = ["expired", "tampered", "other-key", "unsigned", "other-algorithm", "iss", "aud", "token_use", "sub", "sid", "iat", "exp", "empty-sub", "empty-sid", "numeric-sid"])
    fun `JWT 검증 실패는 필터에서 공통 401 오류로 처리한다`(scenario: String) {
        // given
        val account = saveDriver()
        val session = saveSession(account)
        val claims = HashMap<String, Any>(tokens.parseClaims(tokens.createAccessToken(account.id, session.id)))
        when (scenario) {
            "expired" -> {
                claims["iat"] = Date.from(now.minusSeconds(120))
                claims["exp"] = Date.from(now.minusSeconds(1))
            }
            "iss", "token_use" -> claims[scenario] = "other"
            "aud" -> claims[scenario] = listOf("other-service")
            "sub", "sid", "iat", "exp" -> claims.remove(scenario)
            "empty-sub" -> claims["sub"] = " "
            "empty-sid" -> claims["sid"] = " "
            "numeric-sid" -> claims["sid"] = 123
        }
        val token = when (scenario) {
            "other-key" -> Jwts.builder().claims(claims).signWith(SecretKeySpec(ByteArray(32) { 9 }, "HmacSHA256"), Jwts.SIG.HS256).compact()
            "unsigned" -> Jwts.builder().claims(claims).compact()
            "other-algorithm" -> Jwts.builder().claims(claims).signWith(SecretKeySpec(ByteArray(64) { 9 }, "HmacSHA512"), Jwts.SIG.HS512).compact()
            "tampered" -> {
                val parts = tokens.createAccessToken(account.id, session.id).split('.')
                val payload = String(Base64.getUrlDecoder().decode(parts[1]), Charsets.UTF_8).replace(account.id, "other-account")
                val encoded = Base64.getUrlEncoder().withoutPadding().encodeToString(payload.toByteArray(Charsets.UTF_8))
                "${parts[0]}.$encoded.${parts[2]}"
            }
            else -> Jwts.builder().claims(claims).signWith(secretKey, Jwts.SIG.HS256).compact()
        }

        // when & then
        val expectedCode = if (scenario == "expired") "TOKEN_EXPIRED" else "INVALID_TOKEN"
        mockMvc.perform(get(endpoint).header(HttpHeaders.AUTHORIZATION, "Bearer $token"))
            .andExpect(status().isUnauthorized)
            .andExpect(header().string(HttpHeaders.WWW_AUTHENTICATE, "Bearer error=\"invalid_token\""))
            .andExpect(jsonPath("$.code").value(expectedCode))
    }

    @ParameterizedTest
    @ValueSource(strings = ["Bearer", "Bearer ", "Basic credentials", "Bearer a.b.c", "Bearer first,second", "Bearer first second"])
    fun `잘못된 Authorization 형식은 공통 오류로 거부한다`(authorization: String) {
        // when & then
        mockMvc.perform(get(endpoint).header(HttpHeaders.AUTHORIZATION, authorization))
            .andExpect(status().isUnauthorized)
            .andExpect(jsonPath("$.code").value("INVALID_TOKEN"))
    }

    @Test
    fun `중복 Authorization 헤더는 거부한다`() {
        // given
        val account = saveDriver()
        val token = tokens.createAccessToken(account.id, saveSession(account).id)

        // when & then
        mockMvc.perform(get(endpoint).header(HttpHeaders.AUTHORIZATION, "Bearer $token", "Bearer $token"))
            .andExpect(status().isUnauthorized)
            .andExpect(jsonPath("$.code").value("INVALID_TOKEN"))
    }

    @Test
    fun `내 계정 조회는 인증이 없으면 Bearer challenge와 공통 401을 반환한다`() {
        // when & then
        mockMvc.perform(get(endpoint))
            .andExpect(status().isUnauthorized)
            .andExpect(header().string(HttpHeaders.WWW_AUTHENTICATE, "Bearer"))
            .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"))
    }

    @Test
    fun `카카오 로그인은 만료된 Authorization 헤더보다 본문 자격 증명을 사용한다`() {
        // given
        Mockito.`when`(kakaoClient.verify("sdk-token")).thenReturn(socialLogin)
        val expired = JwtTokenProvider(secretKey, Duration.ofMinutes(15), Clock.offset(clock, Duration.ofHours(-1)))
            .createAccessToken("old-account", "old-session")

        // when
        val response = mockMvc.perform(
            post("/api/v1/auth/sessions/kakao")
                .header(HttpHeaders.AUTHORIZATION, "Bearer $expired")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"kakaoAccessToken":"sdk-token"}"""),
        ).andExpect(status().isOk).andReturn().response

        // then
        ownAccounts.add(mapper.readValue(response.contentAsString, TokenPairResponse::class.java).user.accountId)
        Mockito.verify(kakaoClient).verify("sdk-token")
    }

    @Test
    fun `생성 OpenAPI는 내 계정 조회 응답과 Bearer 인증을 노출하고 Principal을 숨긴다`() {
        // when & then
        mockMvc.perform(get("/v3/api-docs"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.paths['$endpoint'].get.security[0].bearerAuth").isArray)
            .andExpect(jsonPath("$.paths['$endpoint'].get.parameters").doesNotExist())
            .andExpect(jsonPath("$.paths['$endpoint'].get.responses['200'].content['application/json'].schema['\$ref']").value("#/components/schemas/AuthUserResponse"))
            .andExpect(jsonPath("$.paths['$endpoint'].get.responses['401']").exists())
    }

    private fun saveDriver(): Account = saveAccount(Account.driver(socialLogin, now.minusSeconds(600)))

    private fun saveAccount(account: Account): Account {
        val saved = transactions.execute { accounts.saveAndFlush(account) }
        ownAccounts.add(saved.id)
        return saved
    }

    private fun saveSession(account: Account, createdAt: Instant = now, expiresAt: Instant = now.plusSeconds(3600)): AuthSession = transactions.execute {
        sessions.saveAndFlush(AuthSession(account.id, refreshTokens.hash(refreshTokens.generate()), createdAt, expiresAt))
    }

    private fun signedClaims(accountId: String, sessionId: String, extras: Map<String, Any>): String {
        val claims = HashMap<String, Any>(tokens.parseClaims(tokens.createAccessToken(accountId, sessionId)))
        claims.putAll(extras)
        return Jwts.builder().claims(claims).signWith(secretKey, Jwts.SIG.HS256).compact()
    }

    private fun expectInvalidToken(token: String) {
        mockMvc.perform(get(endpoint).header(HttpHeaders.AUTHORIZATION, "Bearer $token"))
            .andExpect(status().isUnauthorized)
            .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
            .andExpect(jsonPath("$.code").value("INVALID_TOKEN"))
    }

    @TestConfiguration(proxyBeanMethods = false)
    class TestConfig {
        @Bean
        @Primary
        fun sessionAuthenticationClock(): Clock = Clock.fixed(Instant.parse("2026-11-17T12:00:00Z"), ZoneOffset.UTC)
    }

    @RestController
    class TestController {
        @GetMapping("/api/v1/session-tests/principal")
        fun principal(authentication: Authentication): Map<String, Any?> {
            val principal = authentication.principal as AuthPrincipal
            return mapOf(
                "accountId" to principal.accountId,
                "name" to authentication.name,
                "sessionId" to principal.sessionId,
                "userType" to principal.userType,
                "role" to principal.role,
                "authorities" to authentication.authorities.map { it.authority },
            )
        }

        @PreAuthorize("hasAuthority('TEST_ACCESS')")
        @GetMapping("/api/v1/session-tests/restricted")
        fun restricted(): String = "ok"
    }
}
