package com.allcheck.reception.infrastructure.ai

import com.allcheck.reception.domain.vo.analysis.ReceptionAiOriginal
import com.allcheck.reception.domain.vo.analysis.ReceptionStructuredResult
import com.allcheck.reception.domain.vo.review.WorkType
import com.fasterxml.jackson.annotation.JsonSetter
import com.fasterxml.jackson.annotation.Nulls
import java.time.OffsetDateTime

/** apps/ai/docs/openapi.yaml의 analysisRequestId 계약. jobId 예시는 임의 호환하지 않는다. */
class AiTranscribeRequest(val analysisRequestId: String, val receptionId: String, val receptionVersion: Int, val recordingFileId: String, val recording: AiRecordingSource)

class AiRecordingSource(val downloadUrl: String, val expiresAt: OffsetDateTime, val contentType: String, val sizeBytes: Long, val checksum: AiFileChecksum)

class AiFileChecksum(val value: String, val algorithm: String = "SHA256")

class AiTranscribeResponse(val analysisRequestId: String, val receptionId: String, val receptionVersion: Int, val recordingFileId: String, val status: String, val transcript: String)

class AiAnalyzeRequest(
    val analysisRequestId: String,
    val receptionId: String,
    val receptionVersion: Int,
    val recordingFileId: String,
    val transcriptFileId: String,
    val transcript: String,
    val referenceTime: OffsetDateTime,
    val timeZone: String = "Asia/Seoul",
)

class AiAnalyzeResponse(val analysisRequestId: String, val receptionId: String, val receptionVersion: Int, val recordingFileId: String, val transcriptFileId: String, val status: String, val aiOriginal: AiExtractedFields, val missingFields: List<String>)

class AiExtractedFields(
    @param:JsonSetter(nulls = Nulls.FAIL) val workType: WorkType? = null,
    @param:JsonSetter(nulls = Nulls.FAIL) val symptoms: String? = null,
    @param:JsonSetter(nulls = Nulls.FAIL) val address: String? = null,
    @param:JsonSetter(nulls = Nulls.FAIL) val desiredVisitFrom: OffsetDateTime? = null,
    @param:JsonSetter(nulls = Nulls.FAIL) val desiredVisitTo: OffsetDateTime? = null,
    @param:JsonSetter(nulls = Nulls.FAIL) val notes: String? = null,
) {
    fun result(missingFields: List<String>) = ReceptionStructuredResult(ReceptionAiOriginal(workType, symptoms, address, desiredVisitFrom, desiredVisitTo, notes), missingFields)
}
