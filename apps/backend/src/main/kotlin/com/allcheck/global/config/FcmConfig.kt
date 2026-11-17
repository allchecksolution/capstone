package com.allcheck.global.config

import com.allcheck.global.notification.FcmConnection
import com.google.auth.oauth2.GoogleCredentials
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.client.JdkClientHttpRequestFactory
import org.springframework.web.client.RestClient
import java.io.IOException
import java.net.http.HttpClient
import java.time.Duration

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = ["notification.fcm.enabled"], havingValue = "true")
class FcmConfig {
    @Bean(destroyMethod = "close")
    fun fcmConnection(
        @Value("\${notification.fcm.project-id}") projectId: String,
        @Value("\${notification.fcm.connect-timeout}") connectTimeout: Duration,
        @Value("\${notification.fcm.request-timeout}") requestTimeout: Duration,
    ): FcmConnection {
        require(projectId.matches(Regex("[a-z][a-z0-9-]{4,28}[a-z0-9]"))) { "Specify a valid Firebase project ID." }
        require(connectTimeout > Duration.ZERO && requestTimeout > Duration.ZERO)
        val credentials = try {
            GoogleCredentials.getApplicationDefault().createScoped("https://www.googleapis.com/auth/firebase.messaging")
        } catch (_: IOException) {
            throw IllegalStateException("FCM application credentials are unavailable.")
        }
        val http = HttpClient.newBuilder().connectTimeout(connectTimeout).followRedirects(HttpClient.Redirect.NEVER).build()
        val factory = JdkClientHttpRequestFactory(http)
        factory.setReadTimeout(requestTimeout)
        val client = RestClient.builder().baseUrl("https://fcm.googleapis.com").requestFactory(factory).build()
        return FcmConnection(projectId, client, {
            credentials.refreshIfExpired()
            checkNotNull(credentials.accessToken).tokenValue
        }, http)
    }
}
