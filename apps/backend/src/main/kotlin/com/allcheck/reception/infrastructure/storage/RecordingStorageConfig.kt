package com.allcheck.reception.infrastructure.storage

import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.presigner.S3Presigner
import java.time.Duration

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = ["reception.storage.enabled"], havingValue = "true")
class RecordingStorageConfig(
    @Value("\${reception.storage.region}") private val region: String,
    @Value("\${reception.storage.bucket}") private val bucket: String,
    @Value("\${reception.storage.request-timeout}") private val requestTimeout: Duration,
) {
    init {
        require(region.isNotBlank())
        require(bucket.isNotBlank() && bucket.length <= 63)
        require(requestTimeout > Duration.ZERO)
    }

    @Bean(destroyMethod = "close")
    fun receptionS3Client(): S3Client = S3Client.builder()
        .region(Region.of(region))
        .httpClientBuilder(UrlConnectionHttpClient.builder())
        .overrideConfiguration { it.apiCallTimeout(requestTimeout) }
        .build()

    @Bean(destroyMethod = "close")
    fun receptionS3Presigner(): S3Presigner = S3Presigner.builder().region(Region.of(region)).build()
}
