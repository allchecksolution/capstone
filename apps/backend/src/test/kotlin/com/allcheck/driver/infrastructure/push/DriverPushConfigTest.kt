package com.allcheck.driver.infrastructure.push

import com.allcheck.driver.infrastructure.notification.DriverPushConfig
import com.allcheck.driver.infrastructure.notification.DriverPushProperties
import com.allcheck.driver.infrastructure.notification.DriverPushSchedulingConfig
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.boot.convert.ApplicationConversionService
import org.springframework.boot.test.context.runner.ApplicationContextRunner

class DriverPushConfigTest {
    private val runner = ApplicationContextRunner()
        .withInitializer { it.beanFactory.conversionService = ApplicationConversionService.getSharedInstance() }
        .withUserConfiguration(DriverPushConfig::class.java, DriverPushSchedulingConfig::class.java)

    @Test
    fun `공통 FCM이 꺼져 있으면 전송 작업을 활성화하지 않는다`() {
        // given
        val context = runner.withPropertyValues("notification.fcm.enabled=false")

        // when & then
        context.run {
            assertThat(it).hasNotFailed().hasSingleBean(DriverPushProperties::class.java)
                .doesNotHaveBean(DriverPushSchedulingConfig::class.java)
        }
    }

    @Test
    fun `전송 임대가 공통 HTTP 타임아웃보다 길면 작업을 활성화한다`() {
        // given
        val context = runner.withPropertyValues("notification.fcm.enabled=true", "notification.fcm.request-timeout=10s", "driver.push.lease=1m")

        // when & then
        context.run {
            assertThat(it).hasNotFailed().hasSingleBean(DriverPushSchedulingConfig::class.java)
        }
    }

    @Test
    fun `HTTP 요청 중 임대가 먼저 만료되는 설정을 거부한다`() {
        // given
        val context = runner.withPropertyValues("notification.fcm.enabled=true", "notification.fcm.request-timeout=10s", "driver.push.lease=5s")

        // when & then
        context.run {
            assertThat(it).hasFailed()
            assertThat(it.startupFailure).hasRootCauseInstanceOf(IllegalArgumentException::class.java)
        }
    }
}
