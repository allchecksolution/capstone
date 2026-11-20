package com.allcheck.identity.infrastructure.login.kakao

import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.client.JdkClientHttpRequestFactory
import org.springframework.web.client.RestClient
import java.net.http.HttpClient

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(KakaoProperties::class)
class KakaoClientConfig {
    @Bean(destroyMethod = "close")
    fun kakaoHttpClient(properties: KakaoProperties): HttpClient = HttpClient.newBuilder()
        .connectTimeout(properties.connectTimeout)
        .followRedirects(HttpClient.Redirect.NEVER)
        .build()

    @Bean
    fun kakaoClient(
        @Qualifier("kakaoHttpClient") httpClient: HttpClient,
        properties: KakaoProperties,
    ): KakaoClient {
        val requestFactory = JdkClientHttpRequestFactory(httpClient)
        requestFactory.setReadTimeout(properties.readTimeout)
        val restClient = RestClient.builder()
            .baseUrl("https://kapi.kakao.com")
            .requestFactory(requestFactory)
            .build()
        return KakaoClient(restClient, properties)
    }
}
