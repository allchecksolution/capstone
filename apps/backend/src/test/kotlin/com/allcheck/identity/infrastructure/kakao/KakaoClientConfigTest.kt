package com.allcheck.identity.infrastructure.kakao

import com.allcheck.global.exception.BusinessException
import com.allcheck.identity.exception.IdentityErrorCode
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.ValueSource
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import java.net.http.HttpClient
import java.time.Duration

class KakaoClientConfigTest {
    private val runner = ApplicationContextRunner().withUserConfiguration(KakaoClientConfig::class.java)

    @Test
    fun `숫자 앱 ID와 타임아웃을 바인딩하고 HTTP 리다이렉트를 비활성화한다`() {
        // given
        val configured = runner.withPropertyValues(
            "identity.kakao.app-id=1234",
            "identity.kakao.connect-timeout=PT3S",
            "identity.kakao.read-timeout=PT7S",
        )

        // when
        configured.run { context ->
            // then
            assertThat(context).hasNotFailed().hasSingleBean(KakaoClient::class.java)
            val properties = context.getBean(KakaoProperties::class.java)
            assertThat(properties.appId).isEqualTo(1234)
            assertThat(properties.readTimeout).isEqualTo(Duration.ofSeconds(7))
            val httpClient = context.getBean("kakaoHttpClient", HttpClient::class.java)
            assertThat(httpClient.connectTimeout().orElseThrow()).isEqualTo(Duration.ofSeconds(3))
            assertThat(httpClient.followRedirects()).isEqualTo(HttpClient.Redirect.NEVER)
        }
    }

    @ParameterizedTest
    @ValueSource(strings = ["", "identity.kakao.app-id="])
    fun `앱 ID가 미설정이면 컨텍스트는 유지하되 카카오 인증은 차단한다`(property: String) {
        // given
        val configured = if (property.isEmpty()) runner else runner.withPropertyValues(property)

        // when
        configured.run { context ->
            // then
            assertThat(context).hasNotFailed()
            val client = context.getBean(KakaoClient::class.java)
            val failure = assertThrows<BusinessException> { client.verify("test-token") }
            assertThat(failure.errorCode).isEqualTo(IdentityErrorCode.KAKAO_SERVICE_UNAVAILABLE)
        }
    }

    @ParameterizedTest
    @ValueSource(strings = ["0", "-1", "native-app-key"])
    fun `숫자 앱 ID 대신 앱 키나 유효하지 않은 값을 설정하면 시작하지 않는다`(appId: String) {
        // given & when
        runner.withPropertyValues("identity.kakao.app-id=$appId").run { context ->
            // then
            assertThat(context).hasFailed()
        }
    }

    @ParameterizedTest
    @CsvSource(
        "connect-timeout, PT0S",
        "connect-timeout, -1ms",
        "read-timeout, PT0S",
        "read-timeout, -1ms",
    )
    fun `무제한 또는 음수 타임아웃을 허용하지 않는다`(field: String, timeout: String) {
        // given & when
        runner.withPropertyValues("identity.kakao.$field=$timeout").run { context ->
            // then
            assertThat(context).hasFailed()
        }
    }
}
