package com.allcheck.driver.infrastructure.business

import com.allcheck.driver.domain.vo.BusinessIdentity
import com.allcheck.driver.domain.vo.BusinessOperatingStatus
import com.allcheck.driver.exception.DriverErrorCode
import com.allcheck.global.exception.BusinessException
import com.allcheck.global.exception.ErrorResponse
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import org.junit.jupiter.params.provider.ValueSource
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.http.MediaType
import org.springframework.test.web.client.MockRestServiceServer
import org.springframework.test.web.client.match.MockRestRequestMatchers.content
import org.springframework.test.web.client.match.MockRestRequestMatchers.header
import org.springframework.test.web.client.match.MockRestRequestMatchers.method
import org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo
import org.springframework.test.web.client.response.MockRestResponseCreators.withException
import org.springframework.test.web.client.response.MockRestResponseCreators.withStatus
import org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess
import org.springframework.web.client.RestClient
import tools.jackson.databind.json.JsonMapper
import java.net.http.HttpTimeoutException
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

class NtsBusinessClientTest {
    private val baseUrl = "https://api.odcloud.kr"
    private val endpoint = "$baseUrl/api/nts-businessman/v1/validate?serviceKey=test%2B%2F%3Dkey&returnType=JSON"
    private val now = Instant.parse("2026-11-18T00:00:00Z")
    private val identity = BusinessIdentity("1234567890", LocalDate.of(2020, 1, 15), "테스트대표")
    private lateinit var builder: RestClient.Builder
    private lateinit var server: MockRestServiceServer
    private lateinit var client: NtsBusinessClient

    @BeforeEach
    fun setUp() {
        builder = RestClient.builder().baseUrl(baseUrl)
        server = MockRestServiceServer.bindTo(builder).build()
        client = NtsBusinessClient(builder.build(), NtsProperties(serviceKey = "test+/=key"), Clock.fixed(now, ZoneOffset.UTC))
    }

    @AfterEach
    fun verifyRequests() {
        server.verify()
    }

    @ParameterizedTest
    @EnumSource(BusinessOperatingStatus::class)
    fun `세 가지 필수 정보를 본문에 보내고 일치 결과와 사업자 운영 상태를 분리한다`(status: BusinessOperatingStatus) {
        // given
        val code = when (status) {
            BusinessOperatingStatus.CONTINUING -> "01"
            BusinessOperatingStatus.SUSPENDED -> "02"
            BusinessOperatingStatus.CLOSED -> "03"
        }
        server.expect(requestTo(endpoint))
            .andExpect(method(HttpMethod.POST))
            .andExpect(header(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE))
            .andExpect(content().contentType(MediaType.APPLICATION_JSON))
            .andExpect(content().json("""{"businesses":[{"b_no":"1234567890","start_dt":"20200115","p_nm":"테스트대표"}]}"""))
            .andRespond(withSuccess(validResponse(code), MediaType.APPLICATION_JSON))

        // when
        val result = client.verify(identity)

        // then
        assertThat(result.identity).isSameAs(identity)
        assertThat(result.operatingStatus).isEqualTo(status)
        assertThat(result.verifiedAt).isEqualTo(now)
    }

    @Test
    fun `국세청이 일치하지 않는다고 응답한 경우만 사업자 입력 오류로 분류한다`() {
        // given
        respond("""{"status_code":"OK","request_cnt":1,"data":[{"b_no":"1234567890","valid":"02","valid_msg":"upstream-sensitive-message"}]}""")

        // when & then
        assertFailure(DriverErrorCode.BUSINESS_INFO_INVALID)
    }

    @ParameterizedTest
    @ValueSource(strings = ["{}", "null", "[]", "", "not-json", "{\"status_code\":\"INTERNAL_ERROR\"}", "{\"status_code\":\"OK\",\"request_cnt\":1,\"data\":[]}"])
    fun `잘못된 성공 응답은 검증 실패와 구분해 외부 장애로 처리한다`(body: String) {
        // given
        respond(body)

        // when & then
        assertFailure(DriverErrorCode.BUSINESS_VERIFICATION_UNAVAILABLE)
    }

    @ParameterizedTest
    @ValueSource(strings = ["0", "2", "\"1\"", "1.5", "18446744073709551617", "null"])
    fun `응답 건수가 요청 한 건과 정확히 일치하지 않으면 거부한다`(count: String) {
        // given
        respond(validResponse().replace("\"request_cnt\":1", "\"request_cnt\":$count"))

        // when & then
        assertFailure(DriverErrorCode.BUSINESS_VERIFICATION_UNAVAILABLE)
    }

    @ParameterizedTest
    @ValueSource(strings = ["other-business", "missing-status", "wrong-status-business", "invalid-code", "numeric-valid", "numeric-status"])
    fun `다른 사업자 결과나 잘못된 코드로 인증하지 않는다`(scenario: String) {
        // given
        val response = when (scenario) {
            "other-business" -> validResponse().replace("1234567890", "9999999999")
            "missing-status" -> """{"status_code":"OK","request_cnt":1,"data":[{"b_no":"1234567890","valid":"01"}]}"""
            "wrong-status-business" -> validResponse().replace("\"status\":{\"b_no\":\"1234567890\"", "\"status\":{\"b_no\":\"9999999999\"")
            "invalid-code" -> validResponse().replace("\"valid\":\"01\"", "\"valid\":\"99\"")
            "numeric-valid" -> validResponse().replace("\"valid\":\"01\"", "\"valid\":1")
            else -> validResponse().replace("\"b_stt_cd\":\"01\"", "\"b_stt_cd\":1")
        }
        respond(response)

        // when & then
        assertFailure(DriverErrorCode.BUSINESS_VERIFICATION_UNAVAILABLE)
    }

    @ParameterizedTest
    @ValueSource(ints = [301, 400, 401, 403, 411, 413, 429, 500, 503])
    fun `HTTP 오류와 인증키 오류를 사업자 정보 불일치로 취급하지 않는다`(status: Int) {
        // given
        server.expect(requestTo(endpoint)).andRespond(withStatus(org.springframework.http.HttpStatusCode.valueOf(status)))

        // when & then
        assertFailure(DriverErrorCode.BUSINESS_VERIFICATION_UNAVAILABLE)
    }

    @Test
    fun `연결 및 응답 대기 실패는 민감한 원본 URI 없이 503으로 변환한다`() {
        // given
        server.expect(requestTo(endpoint)).andRespond(withException(HttpTimeoutException("secret-key-and-private-input")))

        // when & then
        val failure = assertFailure(DriverErrorCode.BUSINESS_VERIFICATION_UNAVAILABLE)
        assertThat(failure.cause).isNull()
    }

    @Test
    fun `인증키가 없으면 외부 요청 없이 검증을 차단한다`() {
        // given
        val unconfigured = NtsBusinessClient(builder.build(), NtsProperties())

        // when & then
        val failure = assertThrows<BusinessException> { unconfigured.verify(identity) }
        assertThat(failure.errorCode).isEqualTo(DriverErrorCode.BUSINESS_VERIFICATION_UNAVAILABLE)
    }

    private fun respond(body: String) {
        server.expect(requestTo(endpoint)).andRespond(withSuccess(body, MediaType.APPLICATION_JSON))
    }

    private fun validResponse(status: String = "01"): String = """{"status_code":"OK","request_cnt":1,"valid_cnt":1,"data":[{"b_no":"1234567890","valid":"01","status":{"b_no":"1234567890","b_stt_cd":"$status"}}]}"""

    private fun assertFailure(expected: DriverErrorCode): BusinessException {
        val failure = assertThrows<BusinessException> { client.verify(identity) }
        assertThat(failure.errorCode).isEqualTo(expected)
        val response = JsonMapper.builder().findAndAddModules().build().writeValueAsString(ErrorResponse.from(failure.errorCode))
        assertThat(response).doesNotContain("upstream-sensitive", "1234567890", "test+/=key", "secret-key-and-private-input")
        return failure
    }
}
