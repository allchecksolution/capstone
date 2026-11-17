package com.allcheck.driver.infrastructure.business

import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.client.JdkClientHttpRequestFactory
import org.springframework.web.client.RestClient
import java.net.http.HttpClient

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(NtsProperties::class)
class NtsClientConfig {
    @Bean(destroyMethod = "close")
    fun ntsHttpClient(properties: NtsProperties): HttpClient = HttpClient.newBuilder()
        .connectTimeout(properties.connectTimeout)
        .followRedirects(HttpClient.Redirect.NEVER)
        .build()

    @Bean
    fun ntsBusinessClient(
        @Qualifier("ntsHttpClient") httpClient: HttpClient,
        properties: NtsProperties,
    ): NtsBusinessClient {
        val requestFactory = JdkClientHttpRequestFactory(httpClient)
        requestFactory.setReadTimeout(properties.readTimeout)
        val client = RestClient.builder()
            .baseUrl("https://api.odcloud.kr")
            .requestFactory(requestFactory)
            .build()
        return NtsBusinessClient(client, properties)
    }
}
