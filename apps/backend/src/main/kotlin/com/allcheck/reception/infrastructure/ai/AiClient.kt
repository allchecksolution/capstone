package com.allcheck.reception.infrastructure.ai

import com.allcheck.reception.domain.vo.analysis.ReceptionStructuredResult
import org.springframework.beans.factory.ObjectProvider
import org.springframework.http.MediaType
import org.springframework.stereotype.Component
import org.springframework.web.client.ResourceAccessException
import org.springframework.web.client.RestClient
import org.springframework.web.client.RestClientResponseException
import tools.jackson.core.JacksonException
import tools.jackson.core.StreamReadFeature
import tools.jackson.databind.DeserializationFeature
import tools.jackson.databind.MapperFeature
import tools.jackson.databind.cfg.DateTimeFeature
import tools.jackson.databind.json.JsonMapper
import java.net.SocketTimeoutException
import java.net.http.HttpTimeoutException

/** 동기 요청을 한 번만 호출하며 식별자·상태·원문/원본 계약을 검증한다. 원격 오류 본문을 전파하지 않는다. */
@Component
class AiClient(private val connections: ObjectProvider<AiConnection>) {
    private val mapper = JsonMapper.builder().findAndAddModules()
        .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
        .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
        .disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT)
        .disable(MapperFeature.ALLOW_COERCION_OF_SCALARS)
        .disable(DateTimeFeature.WRITE_DATES_AS_TIMESTAMPS)
        .disable(DateTimeFeature.WRITE_DATES_WITH_CONTEXT_TIME_ZONE)
        .disable(DateTimeFeature.ADJUST_DATES_TO_CONTEXT_TIME_ZONE)
        .build()

    fun transcribe(request: AiTranscribeRequest): String {
        val response = post(connection().transcribe, "/internal/v1/transcribe", request, AiTranscribeResponse::class.java)
        if (response.status != "SUCCEEDED" || response.analysisRequestId != request.analysisRequestId || response.receptionId != request.receptionId || response.receptionVersion != request.receptionVersion || response.recordingFileId != request.recordingFileId || response.transcript.codePointCount(0, response.transcript.length) > 100_000) invalid()
        return response.transcript
    }

    fun analyze(request: AiAnalyzeRequest): ReceptionStructuredResult {
        val response = post(connection().analyze, "/internal/v1/analyze", request, AiAnalyzeResponse::class.java)
        if (response.status != "SUCCEEDED" || response.analysisRequestId != request.analysisRequestId || response.receptionId != request.receptionId || response.receptionVersion != request.receptionVersion || response.recordingFileId != request.recordingFileId || response.transcriptFileId != request.transcriptFileId) invalid()
        return try {
            response.aiOriginal.result(response.missingFields)
        } catch (_: IllegalArgumentException) {
            invalid()
        }
    }

    private fun connection() = connections.ifAvailable ?: throw AiCallException(AiFailureKind.UNAVAILABLE)

    private fun <T> post(client: RestClient, path: String, request: Any, responseType: Class<T>): T {
        val entity = try {
            client.post().uri(path).contentType(MediaType.APPLICATION_JSON).accept(MediaType.APPLICATION_JSON)
                .body(mapper.writeValueAsString(request)).retrieve().toEntity(String::class.java)
        } catch (failure: ResourceAccessException) {
            val timedOut = generateSequence(failure as Throwable) { it.cause }.any { it is HttpTimeoutException || it is SocketTimeoutException }
            throw AiCallException(if (timedOut) AiFailureKind.TIMEOUT else AiFailureKind.FAILED)
        } catch (failure: RestClientResponseException) {
            throw AiCallException(if (failure.statusCode.value() == 504) AiFailureKind.TIMEOUT else AiFailureKind.FAILED)
        }
        if (entity.statusCode.value() != 200 || entity.headers.contentType?.isCompatibleWith(MediaType.APPLICATION_JSON) != true) invalid()
        return try {
            mapper.readValue(entity.body ?: invalid(), responseType)
        } catch (_: JacksonException) {
            invalid()
        }
    }

    private fun invalid(): Nothing = throw AiCallException(AiFailureKind.INVALID_RESPONSE)
}

enum class AiFailureKind { TIMEOUT, FAILED, INVALID_RESPONSE, UNAVAILABLE }

/** URL·내부 키·STT·공급자 본문을 포함하는 cause를 백그라운드/HTTP 로깅 경계로 전달하지 않는다. */
class AiCallException(val kind: AiFailureKind) : RuntimeException("AI request failed: ${kind.name}")
