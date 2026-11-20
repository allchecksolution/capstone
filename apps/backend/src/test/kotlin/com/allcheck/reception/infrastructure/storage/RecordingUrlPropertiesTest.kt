package com.allcheck.reception.infrastructure.storage

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.springframework.boot.convert.ApplicationConversionService
import org.springframework.boot.env.YamlPropertySourceLoader
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.core.io.ClassPathResource
import java.time.Duration

class RecordingUrlPropertiesTest {
    private val contextRunner = ApplicationContextRunner()
        .withUserConfiguration(RecordingUrlProperties::class.java)
        .withInitializer { context ->
            context.beanFactory.conversionService = ApplicationConversionService.getSharedInstance()
            YamlPropertySourceLoader().load("application", ClassPathResource("application.yml")).forEach {
                context.environment.propertySources.addLast(it)
            }
        }

    @Test
    fun `기본 URL 유효기간을 설정 파일에서 읽는다`() {
        // when
        contextRunner.run { context ->
            // then
            assertThat(context).hasNotFailed()
            val properties = context.getBean(RecordingUrlProperties::class.java)
            assertThat(properties.uploadUrlTtl).isEqualTo(Duration.ofMinutes(15))
            assertThat(properties.playbackUrlTtl).isEqualTo(Duration.ofMinutes(10))
            assertThat(properties.aiDownloadUrlTtl).isEqualTo(Duration.ofMinutes(10))
        }
    }

    @Test
    fun `환경 설정으로 각 URL 유효기간을 독립적으로 변경할 수 있다`() {
        // given
        val runner = contextRunner.withPropertyValues(
            "RECEPTION_RECORDING_UPLOAD_URL_TTL=30m",
            "RECEPTION_RECORDING_PLAYBACK_URL_TTL=20m",
            "RECEPTION_RECORDING_AI_DOWNLOAD_URL_TTL=5m",
        )

        // when
        runner.run { context ->
            // then
            assertThat(context).hasNotFailed()
            val properties = context.getBean(RecordingUrlProperties::class.java)
            assertThat(properties.uploadUrlTtl).isEqualTo(Duration.ofMinutes(30))
            assertThat(properties.playbackUrlTtl).isEqualTo(Duration.ofMinutes(20))
            assertThat(properties.aiDownloadUrlTtl).isEqualTo(Duration.ofMinutes(5))
        }
    }

    @ParameterizedTest
    @ValueSource(strings = ["0s", "-1s", "500ms", "1500ms", "8d"])
    fun `S3 서명에 사용할 수 없는 유효기간은 시작 시 거부한다`(ttl: String) {
        // given
        val runner = contextRunner.withPropertyValues("RECEPTION_RECORDING_UPLOAD_URL_TTL=$ttl")

        // when
        runner.run { context ->
            // then
            assertThat(context).hasFailed()
            assertThat(context.startupFailure).hasRootCauseInstanceOf(IllegalArgumentException::class.java)
        }
    }
}
