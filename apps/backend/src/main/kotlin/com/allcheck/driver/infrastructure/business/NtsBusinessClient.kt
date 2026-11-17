package com.allcheck.driver.infrastructure.business

import com.allcheck.driver.domain.vo.BusinessIdentity
import com.allcheck.driver.domain.vo.BusinessOperatingStatus
import com.allcheck.driver.domain.vo.VerifiedBusinessIdentity
import com.allcheck.driver.exception.DriverErrorCode
import com.allcheck.global.exception.BusinessException
import org.springframework.http.MediaType
import org.springframework.web.client.RestClient
import org.springframework.web.client.RestClientException
import tools.jackson.databind.JsonNode
import java.time.Clock
import java.time.format.DateTimeFormatter

/** 진위확인만 수행한다. 가입·심사 저장과 운영 상태별 자격 판단은 application 계층의 책임이다. */
class NtsBusinessClient(
    private val client: RestClient,
    private val properties: NtsProperties,
    private val clock: Clock = Clock.systemUTC(),
) {
    fun verify(identity: BusinessIdentity): VerifiedBusinessIdentity {
        val key = properties.serviceKey?.takeIf { it.isNotBlank() }
            ?: throw BusinessException(DriverErrorCode.BUSINESS_VERIFICATION_UNAVAILABLE)
        val request = mapOf(
            "businesses" to listOf(
                mapOf(
                    "b_no" to identity.registrationNumber,
                    "start_dt" to identity.openedOn.format(DateTimeFormatter.BASIC_ISO_DATE),
                    "p_nm" to identity.representativeName,
                ),
            ),
        )
        return try {
            client.post()
                .uri("/api/nts-businessman/v1/validate?serviceKey={key}&returnType=JSON", key)
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON)
                .body(request)
                .exchange { _, response ->
                    if (response.statusCode.value() != 200) unavailable()
                    parseResult(response.bodyTo(JsonNode::class.java), identity)
                }
        } catch (ex: RestClientException) {
            // 원본 예외에는 인증키가 든 URI나 개인정보가 포함될 수 있어 cause도 외부로 전달하지 않는다.
            throw BusinessException(DriverErrorCode.BUSINESS_VERIFICATION_UNAVAILABLE)
        }
    }

    private fun parseResult(body: JsonNode?, identity: BusinessIdentity): VerifiedBusinessIdentity {
        val count = body?.get("request_cnt")
        if (text(body, "status_code") != "OK" || count == null || !count.isIntegralNumber || !count.canConvertToLong() || count.longValue() != 1L) unavailable()
        val data = body.get("data")
        if (data == null || !data.isArray || data.size() != 1) unavailable()
        val result = data[0]
        if (text(result, "b_no") != identity.registrationNumber) unavailable()
        when (text(result, "valid")) {
            "02" -> throw BusinessException(DriverErrorCode.BUSINESS_INFO_INVALID)
            "01" -> Unit
            else -> unavailable()
        }
        val status = result.get("status")
        if (text(status, "b_no") != identity.registrationNumber) unavailable()
        val operatingStatus = when (text(status, "b_stt_cd")) {
            "01" -> BusinessOperatingStatus.CONTINUING
            "02" -> BusinessOperatingStatus.SUSPENDED
            "03" -> BusinessOperatingStatus.CLOSED
            else -> unavailable()
        }
        return VerifiedBusinessIdentity(identity, operatingStatus, clock.instant())
    }

    private fun text(node: JsonNode?, field: String): String? = node?.get(field)?.takeIf { it.isString }?.stringValue()

    private fun unavailable(): Nothing = throw BusinessException(DriverErrorCode.BUSINESS_VERIFICATION_UNAVAILABLE)
}
