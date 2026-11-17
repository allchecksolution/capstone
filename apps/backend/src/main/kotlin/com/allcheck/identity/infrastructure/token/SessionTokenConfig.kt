package com.allcheck.identity.infrastructure.token

import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(SessionProperties::class)
class SessionTokenConfig {
    @Bean
    fun refreshTokenProvider(): RefreshTokenProvider = RefreshTokenProvider()
}
