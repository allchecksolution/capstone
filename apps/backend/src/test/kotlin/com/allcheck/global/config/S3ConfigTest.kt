package com.allcheck.global.config

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.boot.convert.ApplicationConversionService
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.presigner.S3Presigner

class S3ConfigTest {
    private val runner = ApplicationContextRunner().withUserConfiguration(S3Config::class.java)
        .withInitializer { it.beanFactory.conversionService = ApplicationConversionService.getSharedInstance() }

    @Test
    fun `공통 S3는 접수 활성화나 버킷 설정 없이 독립적으로 생성해 재사용한다`() {
        // given
        val enabled = runner.withPropertyValues("storage.s3.enabled=true", "storage.s3.region=ap-northeast-2", "storage.s3.request-timeout=5s", "reception.storage.enabled=false")

        // when & then
        enabled.run { context ->
            assertThat(context).hasNotFailed().hasSingleBean(S3Client::class.java).hasSingleBean(S3Presigner::class.java)
            assertThat(context.getBean(S3Client::class.java)).isSameAs(context.getBean(S3Client::class.java))
        }
    }

    @Test
    fun `접수의 예전 활성화 설정은 공통 SDK를 생성하지 않는다`() {
        // when & then
        runner.withPropertyValues("reception.storage.enabled=true").run { context ->
            assertThat(context).hasNotFailed().doesNotHaveBean(S3Client::class.java).doesNotHaveBean(S3Presigner::class.java)
        }
    }
}
