package com.allcheck.driver.infrastructure.push

import com.google.auth.oauth2.GoogleCredentials
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.messaging.FirebaseMessaging
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.scheduling.annotation.EnableScheduling
import java.time.Duration

@ConfigurationProperties("push.fcm")
class FcmProperties(
    val enabled: Boolean = false,
    val projectId: String? = null,
    val lease: Duration = Duration.ofMinutes(1),
) {
    init {
        require(!enabled || !projectId.isNullOrBlank()) { "FCM_PROJECT_ID is required when FCM is enabled." }
        require(lease >= Duration.ofSeconds(1)) { "FCM delivery lease must be at least one second." }
    }
}

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(FcmProperties::class)
class FcmConfig {
    @Bean(destroyMethod = "delete")
    @ConditionalOnProperty(prefix = "push.fcm", name = ["enabled"], havingValue = "true")
    fun driverFirebaseApp(properties: FcmProperties): FirebaseApp = FirebaseApp.initializeApp(
        FirebaseOptions.builder().setCredentials(GoogleCredentials.getApplicationDefault())
            .setProjectId(properties.projectId).setConnectTimeout(2000).setReadTimeout(5000).build(),
        "allcheck-driver-notifications",
    )

    @Bean
    @ConditionalOnProperty(prefix = "push.fcm", name = ["enabled"], havingValue = "true")
    fun driverFirebaseMessaging(driverFirebaseApp: FirebaseApp): FirebaseMessaging = FirebaseMessaging.getInstance(driverFirebaseApp)
}

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "push.fcm", name = ["enabled"], havingValue = "true")
@EnableScheduling
class FcmSchedulingConfig
