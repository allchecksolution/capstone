package com.allcheck.global.notification

import org.springframework.beans.factory.ObjectProvider
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.stereotype.Component
import org.springframework.web.client.ResourceAccessException
import org.springframework.web.client.RestClient
import org.springframework.web.client.RestClientResponseException
import tools.jackson.core.JacksonException
import tools.jackson.databind.json.JsonMapper
import java.io.IOException
import java.net.http.HttpClient
import java.time.Clock
import java.time.Duration
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException

/** 한 번의 HTTP 전송만 수행한다. 재시도와 업무 데이터 구성은 호출 도메인이 결정한다. */
@Component
class FcmClient(private val connections: ObjectProvider<FcmConnection>, private val clock: Clock) {
    private val mapper = JsonMapper.builder().build()

    fun send(token: String, data: Map<String, String>, beforeSend: () -> Boolean = { true }): String {
        require(token.isNotBlank() && data.isNotEmpty())
        val connection = connections.ifAvailable ?: throw FcmException(FcmFailure.UNAVAILABLE)
        val accessToken = try {
            connection.accessToken()
        } catch (_: IOException) {
            throw FcmException(FcmFailure.UNAVAILABLE)
        }
        if (accessToken.isBlank() || accessToken.any { it == '\r' || it == '\n' }) throw FcmException(FcmFailure.REJECTED)
        // OAuth 갱신이 오래 걸린 경우에도 호출자가 실제 메시지 전송 직전에 권한/실행권을 재확인할 수 있다.
        if (!beforeSend()) throw FcmException(FcmFailure.CANCELLED)
        val response = try {
            connection.client.post().uri("/v1/projects/${connection.projectId}/messages:send")
                .header(HttpHeaders.AUTHORIZATION, "Bearer $accessToken").contentType(MediaType.APPLICATION_JSON)
                .body(mapper.writeValueAsString(mapOf("message" to mapOf("token" to token, "data" to data))))
                .retrieve().toEntity(String::class.java)
        } catch (_: ResourceAccessException) {
            throw FcmException(FcmFailure.REMOTE_OUTCOME_UNKNOWN)
        } catch (failure: RestClientResponseException) {
            val code = errorCode(failure.responseBodyAsString)
            val kind = when {
                code == "UNREGISTERED" -> FcmFailure.UNREGISTERED
                failure.statusCode.value() == 429 || failure.statusCode.value() >= 500 -> FcmFailure.TEMPORARY_FAILURE
                else -> FcmFailure.REJECTED
            }
            throw FcmException(kind, retryAfter(failure.responseHeaders?.getFirst(HttpHeaders.RETRY_AFTER)))
        }
        if (response.statusCode.value() != 200) throw FcmException(FcmFailure.INVALID_RESPONSE)
        val name = try {
            mapper.readTree(response.body ?: "").path("name").asString("")
        } catch (_: JacksonException) {
            throw FcmException(FcmFailure.REMOTE_OUTCOME_UNKNOWN)
        }
        val prefix = "projects/${connection.projectId}/messages/"
        if (!name.startsWith(prefix) || name.length > 1000 || !name.removePrefix(prefix).matches(Regex("[A-Za-z0-9%:._-]+"))) throw FcmException(FcmFailure.REMOTE_OUTCOME_UNKNOWN)
        return name
    }

    private fun errorCode(body: String): String? = try {
        mapper.readTree(body).path("error").path("details").firstOrNull { it.path("@type").asString("") == "type.googleapis.com/google.firebase.fcm.v1.FcmError" }?.path("errorCode")?.asString()
    } catch (_: JacksonException) {
        null
    }

    private fun retryAfter(value: String?): Duration? {
        if (value == null) return null
        value.toLongOrNull()?.let { return if (it >= 0) Duration.ofSeconds(it) else null }
        return try {
            Duration.between(clock.instant(), ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant()).coerceAtLeast(Duration.ZERO)
        } catch (_: DateTimeParseException) {
            null
        }
    }
}

class FcmConnection(val projectId: String, val client: RestClient, val accessToken: () -> String, private val http: HttpClient? = null) : AutoCloseable {
    override fun close() {
        http?.close()
    }
}

enum class FcmFailure(val retryable: Boolean) {
    UNAVAILABLE(true),
    TEMPORARY_FAILURE(true),
    REMOTE_OUTCOME_UNKNOWN(true),
    UNREGISTERED(false),
    REJECTED(false),
    INVALID_RESPONSE(false),
    CANCELLED(false),
}

/** 등록 토큰·OAuth 토큰·공급자 오류 본문과 원래 cause를 노출하지 않는다. */
class FcmException(val failure: FcmFailure, val retryAfter: Duration? = null) : RuntimeException("FCM send failed: ${failure.name}")
