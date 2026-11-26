package com.allcheck.identity.application

import com.allcheck.global.exception.BusinessException
import com.allcheck.global.security.jwt.JwtTokenProvider
import com.allcheck.identity.UserType
import com.allcheck.identity.application.login.KakaoLoginService
import com.allcheck.identity.domain.entity.account.Account
import com.allcheck.identity.domain.vo.account.SocialLogin
import com.allcheck.identity.exception.IdentityErrorCode
import com.allcheck.identity.infrastructure.login.kakao.KakaoClient
import com.allcheck.identity.infrastructure.repository.account.AccountRepository
import com.allcheck.identity.infrastructure.repository.session.AuthSessionRepository
import com.allcheck.identity.infrastructure.session.RefreshTokenProvider
import com.allcheck.identity.presentation.dto.response.session.TokenPairResponse
import com.allcheck.support.PostgresTestSchema
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import org.junit.jupiter.params.provider.ValueSource
import org.mockito.ArgumentMatchers.anyLong
import org.mockito.Mockito
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.test.annotation.DirtiesContext
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.context.TestPropertySource
import org.springframework.test.context.bean.override.mockito.MockitoBean
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
import tools.jackson.databind.ObjectMapper
import java.time.Duration
import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.sql.DataSource

@SpringBootTest(
    properties = [
        "spring.jpa.show-sql=false",
        "identity.sessions.expiration=PT336H",
    ],
)
@AutoConfigureMockMvc
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@TestPropertySource("classpath:jwt-test.properties")
class KakaoLoginIntegrationTest @Autowired constructor(
    private val mockMvc: MockMvc,
    private val mapper: ObjectMapper,
    private val loginService: KakaoLoginService,
    private val accounts: AccountRepository,
    private val sessions: AuthSessionRepository,
    private val refreshTokens: RefreshTokenProvider,
    transactionManager: PlatformTransactionManager,
) {
    @MockitoBean
    private lateinit var kakaoClient: KakaoClient

    @MockitoSpyBean
    private lateinit var accessTokens: JwtTokenProvider

    private val token = "test-sdk-access-token"
    private val socialLogin = SocialLogin.kakao(300000001)
    private val endpoint = "/api/v1/auth/sessions/kakao"
    private val transactions = TransactionTemplate(transactionManager)

    @BeforeEach
    fun verifiedKakaoIdentity() {
        Mockito.`when`(kakaoClient.verify(token)).thenAnswer {
            check(!TransactionSynchronizationManager.isActualTransactionActive())
            socialLogin
        }
    }

    @AfterEach
    fun removeOwnFixtures() {
        transactions.executeWithoutResult {
            val account = accounts.findBySocialLogin(socialLogin) ?: return@executeWithoutResult
            sessions.deleteAllByIdInBatch(sessions.findAllByAccountId(checkNotNull(account.id)).map { checkNotNull(it.id) })
            accounts.deleteById(checkNotNull(account.id))
        }
    }

    @Test
    fun `신규 카카오 로그인은 계정과 세션을 저장하고 검증 가능한 서비스 토큰을 반환한다`() {
        // when
        val result = mockMvc.perform(post(endpoint).contentType(MediaType.APPLICATION_JSON).content(request()))
            .andExpect(status().isOk)
            .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
            .andExpect(header().string(HttpHeaders.PRAGMA, "no-cache"))
            .andExpect(jsonPath("$.user.userType").value("DRIVER"))
            .andExpect(jsonPath("$.user.role").value(org.hamcrest.Matchers.nullValue()))
            .andExpect(jsonPath("$.accountStatus").doesNotExist())
            .andExpect(jsonPath("$.tokens").doesNotExist())
            .andReturn().response
        val response = mapper.readValue(result.contentAsString, TokenPairResponse::class.java)

        // then
        val account = checkNotNull(accounts.findBySocialLogin(socialLogin))
        val session = sessions.findAllByAccountId(checkNotNull(account.id)).single()
        val claims = accessTokens.parseClaims(response.accessToken)
        assertThat(response.user.accountId).isEqualTo(checkNotNull(account.id)).isNotEqualTo(socialLogin.providerUserId)
        assertThat(response.tokenType).isEqualTo("Bearer")
        assertThat(response.expiresIn).isEqualTo(900)
        assertThat(response.refreshExpiresIn).isEqualTo(1209600)
        assertThat(claims.subject).isEqualTo(checkNotNull(account.id).toString())
        assertThat(claims.get("sid", String::class.java)).isEqualTo(checkNotNull(session.id).toString())
        assertThat(session.refreshTokenHash).isEqualTo(refreshTokens.hash(response.refreshToken))
        assertThat(session.refreshTokenHash.value).isNotEqualTo(response.refreshToken)
        assertThat(Duration.between(session.createdAt, session.expiresAt)).isEqualTo(Duration.ofDays(14))
        assertThat(session.createdAt).isAfterOrEqualTo(account.createdAt)
    }

    @Test
    fun `기존 계정의 재로그인은 같은 계정에 독립적인 새 세션을 만든다`() {
        // given
        val first = loginService.login(token)

        // when
        val second = loginService.login(token)

        // then
        assertThat(second.user.accountId).isEqualTo(first.user.accountId)
        assertThat(second.user.userType).isEqualTo(UserType.DRIVER)
        assertThat(second.refreshToken).isNotEqualTo(first.refreshToken)
        val stored = sessions.findAllByAccountId(first.user.accountId)
        assertThat(stored).hasSize(2).allMatch { it.isActiveAt(Instant.now()) }
    }

    @Test
    fun `비활성 카카오 계정을 재생성하거나 새 세션을 발급하지 않는다`() {
        // given
        val account = transactions.execute {
            val disabled = Account.driver(socialLogin, Instant.now().minusSeconds(60))
            disabled.disable(Instant.now())
            accounts.saveAndFlush(disabled)
        }

        // when
        mockMvc.perform(post(endpoint).contentType(MediaType.APPLICATION_JSON).content(request()))
            .andExpect(status().isForbidden)
            .andExpect(jsonPath("$.code").value("ACCOUNT_DISABLED"))

        // then
        val stored = checkNotNull(accounts.findBySocialLogin(socialLogin))
        assertThat(checkNotNull(stored.id)).isEqualTo(checkNotNull(account.id))
        assertThat(stored.enabled).isFalse()
        assertThat(sessions.findAllByAccountId(checkNotNull(account.id))).isEmpty()
    }

    @ParameterizedTest
    @EnumSource(value = IdentityErrorCode::class, names = ["KAKAO_AUTH_FAILED", "KAKAO_SERVICE_UNAVAILABLE"])
    fun `카카오 검증 실패는 계정 변경 없이 공통 오류 응답으로 반환한다`(error: IdentityErrorCode) {
        // given
        Mockito.`when`(kakaoClient.verify(token)).thenThrow(BusinessException(error))

        // when
        mockMvc.perform(post(endpoint).contentType(MediaType.APPLICATION_JSON).content(request()))
            .andExpect(status().`is`(error.status.value()))
            .andExpect(jsonPath("$.code").value(error.name))

        // then
        assertThat(accounts.findBySocialLogin(socialLogin)).isNull()
    }

    @Test
    fun `JWT 발급 실패 시 신규 계정과 세션 저장을 함께 롤백한다`() {
        // given
        var accountId: Long? = null
        var sessionId: Long? = null
        Mockito.doAnswer {
            accountId = it.getArgument(0)
            sessionId = it.getArgument(1)
            throw IllegalStateException("test-signing-failure")
        }.`when`(accessTokens).createAccessToken(anyLong(), anyLong())

        // when
        mockMvc.perform(post(endpoint).contentType(MediaType.APPLICATION_JSON).content(request()))
            .andExpect(status().isInternalServerError)
            .andExpect(jsonPath("$.code").value("INTERNAL_SERVER_ERROR"))

        // then
        assertThat(accounts.existsById(checkNotNull(accountId))).isFalse()
        assertThat(sessions.existsById(checkNotNull(sessionId))).isFalse()
    }

    @Test
    fun `호출자가 트랜잭션 안에 있어도 카카오 호출은 밖에서 수행한다`() {
        // when
        val response = transactions.execute { loginService.login(token) }

        // then
        assertThat(response.user.accountId).isPositive()
        Mockito.verify(kakaoClient).verify(token)
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `동시 카카오 로그인은 계정 하나와 요청별 세션을 유지한다`(existing: Boolean) {
        // given
        if (existing) transactions.execute { accounts.saveAndFlush(Account.driver(socialLogin, Instant.now())) }
        val barrier = CountDownLatch(2)
        Mockito.`when`(kakaoClient.verify(token)).thenAnswer {
            check(!TransactionSynchronizationManager.isActualTransactionActive())
            barrier.countDown()
            check(barrier.await(5, TimeUnit.SECONDS))
            socialLogin
        }
        val executor = Executors.newFixedThreadPool(2)

        try {
            // when
            val futures = (1..2).map { executor.submit<TokenPairResponse> { loginService.login(token) } }
            val responses = futures.map { it.get(15, TimeUnit.SECONDS) }

            // then
            val account = checkNotNull(accounts.findBySocialLogin(socialLogin))
            assertThat(responses.map { it.user.accountId }).containsOnly(checkNotNull(account.id))
            assertThat(responses.map { it.refreshToken }).doesNotHaveDuplicates()
            assertThat(sessions.findAllByAccountId(checkNotNull(account.id))).hasSize(2)
        } finally {
            executor.shutdownNow()
            check(executor.awaitTermination(10, TimeUnit.SECONDS))
        }
    }

    @ParameterizedTest
    @ValueSource(
        strings = [
            "{}",
            "{\"kakaoAccessToken\":null}",
            "{\"kakaoAccessToken\":\"\"}",
            "{\"kakaoAccessToken\":\"token with spaces\"}",
            "{\"kakaoAccessToken\":\"token\",\"userType\":\"ADMIN\"}",
            "{\"kakaoAccessToken\":\"token\",\"accountId\":\"other-account\"}",
            "{\"kakaoAccessToken\":\"token\\r\\nInjected: header\"}",
        ],
    )
    fun `잘못된 입력과 클라이언트 계정 지정은 카카오 호출 전에 거부한다`(body: String) {
        // when
        mockMvc.perform(post(endpoint).contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().isBadRequest)

        // then
        Mockito.verifyNoInteractions(kakaoClient)
    }

    @Test
    fun `길이 제한을 초과한 토큰은 카카오 호출 전에 거부한다`() {
        // when
        mockMvc.perform(post(endpoint).contentType(MediaType.APPLICATION_JSON).content(request("a".repeat(4001))))
            .andExpect(status().isBadRequest)

        // then
        Mockito.verifyNoInteractions(kakaoClient)
    }

    @ParameterizedTest
    @ValueSource(strings = ["/api/v1/auth/accounts/me"])
    fun `보호 인증 경로와 다른 HTTP 메서드는 함께 공개하지 않는다`(path: String) {
        // when & then
        mockMvc.perform(post(path).contentType(MediaType.APPLICATION_JSON).content("{}"))
            .andExpect(status().isUnauthorized)
        mockMvc.perform(get(endpoint)).andExpect(status().isUnauthorized)
    }

    @Test
    fun `생성 OpenAPI는 카카오 로그인과 공통 Bearer 인증 스키마를 명시한다`() {
        // when & then
        mockMvc.perform(get("/v3/api-docs"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.paths['$endpoint'].post.security").isEmpty())
            .andExpect(jsonPath("$.components.securitySchemes.bearerAuth.scheme").value("bearer"))
    }

    private fun request(accessToken: String = token): String = """{"kakaoAccessToken":"$accessToken"}"""

    companion object {
        private val database = PostgresTestSchema("kakao_login")

        @JvmStatic
        @DynamicPropertySource
        fun properties(registry: DynamicPropertyRegistry) = database.register(registry)

        @JvmStatic
        @AfterAll
        fun removeTestSchema(@Autowired dataSource: DataSource) = database.remove(dataSource)
    }
}
