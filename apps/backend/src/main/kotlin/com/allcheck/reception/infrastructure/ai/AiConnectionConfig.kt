package com.allcheck.reception.infrastructure.ai

import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.client.JdkClientHttpRequestFactory
import org.springframework.web.client.RestClient
import java.net.URI
import java.net.http.HttpClient
import java.time.Duration

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = ["reception.ai.enabled"], havingValue = "true")
class AiConnectionConfig {
    @Bean
    fun receptionAiConnection(
        @Value("\${reception.ai.base-url}") baseUrl: String,
        @Value("\${reception.ai.internal-api-key}") apiKey: String,
        @Value("\${reception.ai.contract}") contract: String,
        @Value("\${reception.ai.connect-timeout}") connectTimeout: Duration,
        @Value("\${reception.ai.stt-timeout}") sttTimeout: Duration,
        @Value("\${reception.ai.structure-timeout}") structureTimeout: Duration,
    ): AiConnection {
        require(contract == "analysis-request-id-v1") { "Confirm the AI wire contract before enabling it." }
        require(apiKey.isNotBlank()) { "AI internal authentication is required." }
        require(apiKey.none { it == '\r' || it == '\n' }) { "AI authentication must be a single header value." }
        val origin = try {
            URI.create(baseUrl)
        } catch (_: IllegalArgumentException) {
            throw IllegalArgumentException("AI base URL must be a valid origin.")
        }
        require(origin.scheme in setOf("http", "https") && origin.host != null && origin.rawUserInfo == null && origin.rawQuery == null && origin.rawFragment == null && origin.path in setOf("", "/")) { "AI base URL must be an HTTP origin without credentials or query." }
        require(origin.scheme == "https" || origin.host in setOf("localhost", "127.0.0.1", "::1", "[::1]")) { "AI communication requires HTTPS except for local loopback development." }
        listOf(connectTimeout, sttTimeout, structureTimeout).forEach { require(it > Duration.ZERO) { "AI timeouts must be positive." } }
        val http = HttpClient.newBuilder().connectTimeout(connectTimeout).followRedirects(HttpClient.Redirect.NEVER).build()
        return AiConnection(client(origin, apiKey, http, sttTimeout), client(origin, apiKey, http, structureTimeout), http)
    }

    private fun client(origin: URI, apiKey: String, http: HttpClient, timeout: Duration): RestClient {
        val factory = JdkClientHttpRequestFactory(http)
        factory.setReadTimeout(timeout)
        return RestClient.builder().baseUrl(origin.toString()).requestFactory(factory).defaultHeader("X-Internal-Api-Key", apiKey).build()
    }
}

class AiConnection(val transcribe: RestClient, val analyze: RestClient, private val http: HttpClient? = null) : AutoCloseable {
    override fun close() {
        http?.close()
    }
}
