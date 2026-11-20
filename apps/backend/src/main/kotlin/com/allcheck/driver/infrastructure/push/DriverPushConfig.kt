package com.allcheck.driver.infrastructure.push

import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Configuration
import org.springframework.scheduling.annotation.EnableScheduling
import java.time.Duration

@ConfigurationProperties("driver.push")
class DriverPushProperties(val lease: Duration = Duration.ofMinutes(1)) {
    init {
        require(lease >= Duration.ofSeconds(1)) { "Driver delivery lease must be at least one second." }
    }
}

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(DriverPushProperties::class)
class DriverPushConfig

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = ["notification.fcm.enabled"], havingValue = "true")
@EnableScheduling
class DriverPushSchedulingConfig(properties: DriverPushProperties, @Value("\${notification.fcm.request-timeout}") timeout: Duration) {
    init {
        require(properties.lease > timeout) { "Driver delivery lease must exceed the shared FCM request timeout." }
    }
}
