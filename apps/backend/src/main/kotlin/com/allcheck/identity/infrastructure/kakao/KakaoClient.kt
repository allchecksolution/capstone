package com.allcheck.identity.infrastructure.kakao

import com.allcheck.global.exception.BusinessException
import com.allcheck.identity.domain.vo.SocialLogin
import com.allcheck.identity.exception.IdentityErrorCode
import org.springframework.http.MediaType
import org.springframework.web.client.RestClient
import org.springframework.web.client.RestClientException
import tools.jackson.databind.JsonNode

class KakaoClient(
    private val restClient: RestClient,
    private val properties: KakaoProperties,
) {
    fun verify(accessToken: String): SocialLogin {
        val expectedAppId = properties.appId
            ?: throw BusinessException(IdentityErrorCode.KAKAO_SERVICE_UNAVAILABLE)
        if (accessToken.isEmpty() || accessToken.length > 4000 || accessToken.any { it !in '!'..'~' }) {
            throw BusinessException(IdentityErrorCode.KAKAO_AUTH_FAILED)
        }

        return try {
            restClient.get()
                .uri("/v1/user/access_token_info")
                .headers { it.setBearerAuth(accessToken) }
                .accept(MediaType.APPLICATION_JSON)
                .exchange { _, response ->
                    when (response.statusCode.value()) {
                        200 -> verifiedLogin(response.bodyTo(JsonNode::class.java), expectedAppId)
                        401 -> throw BusinessException(IdentityErrorCode.KAKAO_AUTH_FAILED)
                        400 -> rejectKakaoError(response.bodyTo(JsonNode::class.java))
                        else -> throw BusinessException(IdentityErrorCode.KAKAO_SERVICE_UNAVAILABLE)
                    }
                }
        } catch (exception: RestClientException) {
            // 공통 예외 처리기는 원본 응답·cause를 로그나 클라이언트 응답에 노출하지 않는다.
            throw BusinessException(IdentityErrorCode.KAKAO_SERVICE_UNAVAILABLE, exception)
        }
    }

    private fun verifiedLogin(body: JsonNode?, expectedAppId: Long): SocialLogin {
        val userId = requiredLong(body, "id")
        val appId = requiredLong(body, "app_id")
        val expiresIn = requiredLong(body, "expires_in")
        if (userId <= 0 || appId <= 0) {
            throw BusinessException(IdentityErrorCode.KAKAO_SERVICE_UNAVAILABLE)
        }
        if (expiresIn <= 0 || appId != expectedAppId) {
            throw BusinessException(IdentityErrorCode.KAKAO_AUTH_FAILED)
        }
        return SocialLogin.kakao(userId)
    }

    private fun rejectKakaoError(body: JsonNode?): Nothing {
        // Kakao -1은 HTTP 400으로도 반환되는 일시 장애다. 확인된 자격 증명 오류만 401로 변환한다.
        val code = requiredLong(body, "code")
        if (code == -401L || code == -2L) {
            throw BusinessException(IdentityErrorCode.KAKAO_AUTH_FAILED)
        }
        throw BusinessException(IdentityErrorCode.KAKAO_SERVICE_UNAVAILABLE)
    }

    private fun requiredLong(body: JsonNode?, field: String): Long {
        val value = body?.get(field)
        if (value == null || !value.isIntegralNumber || !value.canConvertToLong()) {
            throw BusinessException(IdentityErrorCode.KAKAO_SERVICE_UNAVAILABLE)
        }
        return value.longValue()
    }
}
