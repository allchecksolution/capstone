package com.allcheck.identity.infrastructure.kakao

import com.allcheck.global.exception.BusinessException
import com.allcheck.global.exception.ErrorResponse
import com.allcheck.identity.domain.vo.SocialProvider
import com.allcheck.identity.exception.IdentityErrorCode
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.ValueSource
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.test.web.client.MockRestServiceServer
import org.springframework.test.web.client.match.MockRestRequestMatchers.header
import org.springframework.test.web.client.match.MockRestRequestMatchers.method
import org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo
import org.springframework.test.web.client.response.MockRestResponseCreators.withException
import org.springframework.test.web.client.response.MockRestResponseCreators.withStatus
import org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess
import org.springframework.web.client.ResourceAccessException
import org.springframework.web.client.RestClient
import java.net.ConnectException
import java.net.http.HttpTimeoutException

class KakaoClientTest {
    private val token = "test-kakao-access-token"
    private val endpoint = "https://kapi.kakao.com/v1/user/access_token_info"
    private lateinit var builder: RestClient.Builder
    private lateinit var server: MockRestServiceServer
    private lateinit var client: KakaoClient

    @BeforeEach
    fun setUp() {
        builder = RestClient.builder().baseUrl("https://kapi.kakao.com")
        server = MockRestServiceServer.bindTo(builder).build()
        client = KakaoClient(builder.build(), KakaoProperties(appId = 1234))
    }

    @AfterEach
    fun verifyRequests() {
        server.verify()
    }

    @Test
    fun `카카오가 확인한 회원번호만 반환하고 토큰은 Bearer 헤더로 보낸다`() {
        // given
        server.expect(requestTo(endpoint))
            .andExpect(method(HttpMethod.GET))
            .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer $token"))
            .andExpect(header(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE))
            .andRespond(withSuccess("""{"id":123456789,"app_id":1234,"expires_in":7199,"extra":"ignored"}""", MediaType.APPLICATION_JSON))

        // when
        val login = client.verify(token)

        // then
        assertThat(login.provider).isEqualTo(SocialProvider.KAKAO)
        assertThat(login.providerUserId).isEqualTo("123456789")
    }

    @Test
    fun `유효한 토큰이어도 다른 카카오 앱에서 발급됐다면 거부한다`() {
        // given
        expectResponse(HttpStatus.OK, """{"id":123456789,"app_id":9999,"expires_in":7199}""")

        // when & then
        assertFailure(IdentityErrorCode.KAKAO_AUTH_FAILED)
    }

    @ParameterizedTest
    @ValueSource(longs = [0, -1])
    fun `남은 유효 시간이 없으면 만료 토큰으로 처리한다`(expiresIn: Long) {
        // given
        expectResponse(HttpStatus.OK, """{"id":123456789,"app_id":1234,"expires_in":$expiresIn}""")

        // when & then
        assertFailure(IdentityErrorCode.KAKAO_AUTH_FAILED)
    }

    @ParameterizedTest
    @ValueSource(
        strings = [
            "{}",
            "null",
            "[]",
            "{\"id\":123456789,\"app_id\":1234}",
            "{\"id\":123456789,\"expires_in\":7199}",
            "{\"app_id\":1234,\"expires_in\":7199}",
            "{\"id\":null,\"app_id\":1234,\"expires_in\":7199}",
            "{\"id\":0,\"app_id\":1234,\"expires_in\":7199}",
            "{\"id\":123456789,\"app_id\":0,\"expires_in\":7199}",
            "{\"id\":\"123456789\",\"app_id\":1234,\"expires_in\":7199}",
            "{\"id\":123456789.5,\"app_id\":1234,\"expires_in\":7199}",
            "{\"id\":9223372036854775808,\"app_id\":1234,\"expires_in\":7199}",
            "{\"id\":123456789,\"app_id\":\"1234\",\"expires_in\":7199}",
            "{\"id\":123456789,\"app_id\":1234,\"expires_in\":true}",
            "upstream-sensitive-invalid-json",
            "",
        ],
    )
    fun `성공 상태라도 응답 계약이 깨졌다면 카카오 응답 오류로 처리한다`(body: String) {
        // given
        expectResponse(HttpStatus.OK, body)

        // when & then
        assertFailure(IdentityErrorCode.KAKAO_SERVICE_UNAVAILABLE)
    }

    @ParameterizedTest
    @CsvSource(
        "400, -1, KAKAO_SERVICE_UNAVAILABLE",
        "400, -2, KAKAO_AUTH_FAILED",
        "400, -401, KAKAO_AUTH_FAILED",
        "400, -999, KAKAO_SERVICE_UNAVAILABLE",
        "401, -401, KAKAO_AUTH_FAILED",
        "403, -403, KAKAO_SERVICE_UNAVAILABLE",
        "404, -404, KAKAO_SERVICE_UNAVAILABLE",
        "429, -10, KAKAO_SERVICE_UNAVAILABLE",
        "500, -401, KAKAO_SERVICE_UNAVAILABLE",
        "503, -1, KAKAO_SERVICE_UNAVAILABLE",
    )
    fun `HTTP 상태와 카카오 오류 코드로 자격 증명 실패와 외부 장애를 구분한다`(
        status: Int,
        code: Long,
        expected: IdentityErrorCode,
    ) {
        // given
        expectResponse(HttpStatus.valueOf(status), """{"code":$code,"msg":"upstream-sensitive-error"}""")

        // when & then
        assertFailure(expected)
    }

    @ParameterizedTest
    @ValueSource(strings = ["{}", "{\"code\":null}", "{\"code\":\"-401\"}", "upstream-sensitive-invalid-json"])
    fun `해석할 수 없는 카카오 오류 응답을 사용자 인증 실패로 단정하지 않는다`(body: String) {
        // given
        expectResponse(HttpStatus.BAD_REQUEST, body)

        // when & then
        assertFailure(IdentityErrorCode.KAKAO_SERVICE_UNAVAILABLE)
    }

    @Test
    fun `JSON이 아닌 응답은 외부 장애로 처리한다`() {
        // given
        server.expect(requestTo(endpoint))
            .andRespond(withSuccess("<html>upstream-sensitive-error</html>", MediaType.TEXT_HTML))

        // when & then
        assertFailure(IdentityErrorCode.KAKAO_SERVICE_UNAVAILABLE)
    }

    @Test
    fun `리다이렉트 응답은 인증 성공으로 처리하지 않는다`() {
        // given
        server.expect(requestTo(endpoint))
            .andRespond(withStatus(HttpStatus.FOUND).header(HttpHeaders.LOCATION, "https://other.example.invalid"))

        // when & then
        assertFailure(IdentityErrorCode.KAKAO_SERVICE_UNAVAILABLE)
    }

    @Test
    fun `네트워크 타임아웃은 인증 실패가 아닌 일시 장애로 처리한다`() {
        // given
        server.expect(requestTo(endpoint)).andRespond(withException(HttpTimeoutException("upstream-sensitive-error")))

        // when
        val failure = assertFailure(IdentityErrorCode.KAKAO_SERVICE_UNAVAILABLE)

        // then
        assertThat(failure.cause).isInstanceOf(ResourceAccessException::class.java)
    }

    @Test
    fun `연결 실패도 공개 메시지와 자격 증명을 분리하여 처리한다`() {
        // given
        server.expect(requestTo(endpoint)).andRespond(withException(ConnectException("upstream-sensitive-error")))

        // when & then
        assertFailure(IdentityErrorCode.KAKAO_SERVICE_UNAVAILABLE)
    }

    @ParameterizedTest
    @ValueSource(strings = ["", " ", "token with spaces", "token\r\nInjected: header", "한글토큰"])
    fun `헤더에 사용할 수 없는 토큰은 카카오에 전송하지 않는다`(accessToken: String) {
        // when & then
        assertThat(assertThrows<BusinessException> { client.verify(accessToken) }.errorCode)
            .isEqualTo(IdentityErrorCode.KAKAO_AUTH_FAILED)
    }

    @Test
    fun `허용 길이를 초과한 토큰은 전송하지 않는다`() {
        // when & then
        assertThat(assertThrows<BusinessException> { client.verify("a".repeat(4001)) }.errorCode)
            .isEqualTo(IdentityErrorCode.KAKAO_AUTH_FAILED)
    }

    @Test
    fun `서버 앱 ID가 없으면 외부 요청이나 인증 성공을 허용하지 않는다`() {
        // given
        client = KakaoClient(builder.build(), KakaoProperties())

        // when & then
        assertFailure(IdentityErrorCode.KAKAO_SERVICE_UNAVAILABLE)
    }

    private fun expectResponse(status: HttpStatus, body: String) {
        server.expect(requestTo(endpoint))
            .andExpect(method(HttpMethod.GET))
            .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer $token"))
            .andRespond(withStatus(status).body(body).contentType(MediaType.APPLICATION_JSON))
    }

    private fun assertFailure(expected: IdentityErrorCode): BusinessException {
        val failure = assertThrows<BusinessException> { client.verify(token) }
        assertThat(failure.errorCode).isEqualTo(expected)
        assertThat(failure.message).isEqualTo(expected.message).doesNotContain(token, "upstream-sensitive")
        val response = ErrorResponse.from(failure.errorCode)
        assertThat(response.code).isEqualTo(expected.name)
        assertThat(response.message).isEqualTo(expected.message)
        return failure
    }
}
