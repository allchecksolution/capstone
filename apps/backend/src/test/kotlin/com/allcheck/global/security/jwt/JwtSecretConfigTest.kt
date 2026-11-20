package com.allcheck.global.security.jwt

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import java.util.Base64
import javax.crypto.SecretKey

class JwtSecretConfigTest {
    @ParameterizedTest
    @ValueSource(ints = [32, 64])
    fun `Base64로 인코딩된 256비트 이상의 키를 표준 SecretKey Bean으로 생성한다`(size: Int) {
        // given
        val keyBytes = ByteArray(size) { it.toByte() }
        val encodedSecret = Base64.getEncoder().encodeToString(keyBytes)

        // when
        ApplicationContextRunner()
            .withUserConfiguration(JwtSecretConfig::class.java)
            .withPropertyValues("jwt.secret=$encodedSecret")
            .run { context ->
                // then
                assertThat(context).hasNotFailed().hasSingleBean(SecretKey::class.java)
                val secretKey = context.getBean(SecretKey::class.java)
                assertThat(secretKey.algorithm).isEqualTo("HmacSHA256")
                assertThat(secretKey.encoded).isEqualTo(keyBytes)
            }
    }

    @ParameterizedTest
    @ValueSource(strings = ["", "jwt.secret=", "jwt.secret=   ", "jwt.secret=invalid%base64", "jwt.secret=c2hvcnQ="])
    fun `누락되거나 잘못된 Secret은 명확한 설정 오류로 컨텍스트 로딩을 중단한다`(property: String) {
        // given
        val runner = ApplicationContextRunner().withUserConfiguration(JwtSecretConfig::class.java)
        val configuredRunner = if (property.isEmpty()) runner else runner.withPropertyValues(property)

        // when
        configuredRunner.run { context ->
            // then
            assertThat(context).hasFailed()
            assertThat(context.startupFailure)
                .hasRootCauseInstanceOf(IllegalArgumentException::class.java)
                .hasStackTraceContaining("JWT_SECRET")
        }
    }

    @ParameterizedTest
    @ValueSource(ints = [1, 31])
    fun `길이는 Base64 문자열이 아닌 디코딩된 바이트를 기준으로 검증한다`(size: Int) {
        // given
        val encodedSecret = Base64.getEncoder().encodeToString(ByteArray(size) { it.toByte() })

        // when & then
        assertThatThrownBy { JwtSecretConfig().jwtSecretKey(encodedSecret) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("32 bytes (256 bits)")
    }
}
