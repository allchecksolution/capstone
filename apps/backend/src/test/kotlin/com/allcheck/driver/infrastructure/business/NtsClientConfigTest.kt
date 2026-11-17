package com.allcheck.driver.infrastructure.business

import com.allcheck.driver.domain.vo.BusinessIdentity
import com.allcheck.driver.exception.DriverErrorCode
import com.allcheck.global.exception.BusinessException
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import java.net.http.HttpClient
import java.time.LocalDate

class NtsClientConfigTest {
    private val runner = ApplicationContextRunner().withUserConfiguration(NtsClientConfig::class.java)

    @Test
    fun `기본 타임아웃과 리다이렉트 금지를 적용하고 인증키 없이 시작할 수 있다`() {
        // when
        runner.run { context ->
            // then
            assertThat(context).hasNotFailed().hasSingleBean(NtsBusinessClient::class.java)
            val httpClient = context.getBean("ntsHttpClient", HttpClient::class.java)
            assertThat(httpClient.followRedirects()).isEqualTo(HttpClient.Redirect.NEVER)
            assertThat(httpClient.connectTimeout().orElseThrow().seconds).isEqualTo(2)
            val client = context.getBean(NtsBusinessClient::class.java)
            val failure = assertThrows<BusinessException> {
                client.verify(BusinessIdentity("1234567890", LocalDate.of(2020, 1, 15), "테스트대표"))
            }
            assertThat(failure.errorCode).isEqualTo(DriverErrorCode.BUSINESS_VERIFICATION_UNAVAILABLE)
        }
    }

    @ParameterizedTest
    @ValueSource(strings = ["driver.nts.connect-timeout=PT0S", "driver.nts.read-timeout=-1ms"])
    fun `무제한 또는 음수 타임아웃은 설정 오류로 차단한다`(property: String) {
        // when & then
        runner.withPropertyValues(property).run { context -> assertThat(context).hasFailed() }
    }
}
