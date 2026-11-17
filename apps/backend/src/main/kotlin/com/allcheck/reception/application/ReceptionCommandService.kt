package com.allcheck.reception.application

import com.allcheck.global.exception.BusinessException
import com.allcheck.global.exception.GlobalErrorCode
import com.allcheck.reception.application.recording.ReceptionRecordingTransactions
import com.allcheck.reception.application.recording.RecordingUploadMetadata
import com.allcheck.reception.application.review.ReceptionService
import com.allcheck.reception.domain.entity.ReceptionCommandReceipt
import com.allcheck.reception.domain.vo.ReceptionExternalId
import com.allcheck.reception.domain.vo.review.ReceptionConfirmationData
import com.allcheck.reception.domain.vo.review.ReceptionCreationData
import com.allcheck.reception.exception.ReceptionErrorCode
import com.allcheck.reception.infrastructure.repository.ReceptionCommandReceiptRepository
import com.allcheck.reception.infrastructure.repository.ReceptionRepository
import com.allcheck.reception.presentation.dto.response.recording.AcceptedRecordingAnalysisResponse
import com.allcheck.reception.presentation.dto.response.recording.RecordingUploadCompletedResponse
import com.allcheck.reception.presentation.dto.response.review.ReceptionConfirmedResponse
import com.allcheck.reception.presentation.dto.response.review.ReceptionDetailResponse
import com.allcheck.reception.presentation.dto.response.review.ReceptionReopenedResponse
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import tools.jackson.core.type.TypeReference
import tools.jackson.databind.cfg.DateTimeFeature
import tools.jackson.databind.json.JsonMapper
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.time.Clock

/** POST 업무 변경과 중복 판별 정보를 함께 커밋한다. 업로드 예약 응답 기록에는 URL을 포함하지 않는다. */
@Service
@Transactional(readOnly = true)
class ReceptionCommandService(
    private val service: ReceptionService,
    private val receipts: ReceptionCommandReceiptRepository,
    private val receptions: ReceptionRepository,
    private val clock: Clock,
    private val recordings: ReceptionRecordingTransactions,
) {
    private val mapper = JsonMapper.builder().findAndAddModules()
        .disable(DateTimeFeature.WRITE_DATES_AS_TIMESTAMPS)
        .disable(DateTimeFeature.WRITE_DATES_WITH_CONTEXT_TIME_ZONE)
        .disable(DateTimeFeature.ADJUST_DATES_TO_CONTEXT_TIME_ZONE)
        .build()

    @Transactional
    fun create(adminId: Long, key: String, data: ReceptionCreationData): ReceptionCommandResult<ReceptionDetailResponse> = execute(adminId, "CREATE", 0, key, data, ReceptionDetailResponse::class.java) {
        val result = service.create(adminId, data)
        ReceptionExternalId.RECEPTION.parse(result.receptionId) to result
    }

    @Transactional
    fun confirm(adminId: Long, receptionId: Long, key: String, expectedVersion: Int, data: ReceptionConfirmationData): ReceptionCommandResult<ReceptionConfirmedResponse> = execute(adminId, "CONFIRM", receptionId, key, mapOf("expectedVersion" to expectedVersion, "finalData" to data), ReceptionConfirmedResponse::class.java) {
        receptionId to service.confirm(adminId, receptionId, expectedVersion, data)
    }

    @Transactional
    fun reopen(adminId: Long, receptionId: Long, key: String, expectedVersion: Int): ReceptionCommandResult<ReceptionReopenedResponse> = execute(adminId, "REOPEN", receptionId, key, mapOf("expectedVersion" to expectedVersion), ReceptionReopenedResponse::class.java) {
        receptionId to service.reopen(adminId, receptionId, expectedVersion)
    }

    fun completionReplay(adminId: Long, receptionId: Long, recordingId: Long, key: String, expectedVersion: Int): ReceptionCommandResult<RecordingUploadCompletedResponse>? {
        validateKey(key)
        ensureOwned(adminId, receptionId)
        val hash = digest(mapper.writeValueAsBytes(completionInput(recordingId, expectedVersion)))
        return replay(adminId, "COMPLETE_RECORDING", receptionId, key, hash, RecordingUploadCompletedResponse::class.java)
    }

    @Transactional
    fun completeRecording(adminId: Long, receptionId: Long, recordingId: Long, key: String, expectedVersion: Int, verified: RecordingUploadMetadata): ReceptionCommandResult<RecordingUploadCompletedResponse> = execute(adminId, "COMPLETE_RECORDING", receptionId, key, completionInput(recordingId, expectedVersion), RecordingUploadCompletedResponse::class.java) {
        val accepted = recordings.complete(adminId, receptionId, recordingId, expectedVersion, verified)
        val id = ReceptionExternalId.RECEPTION.format(receptionId)
        val fileId = ReceptionExternalId.RECORDING.format(recordingId)
        val analysisId = ReceptionExternalId.ANALYSIS.format(accepted.analysisId)
        val referenceTime = accepted.referenceTime.atOffset(java.time.ZoneOffset.ofHours(9))
        receptionId to RecordingUploadCompletedResponse(fileId, "SUCCEEDED", referenceTime, referenceTime, AcceptedRecordingAnalysisResponse(analysisId, id, accepted.receptionVersion, fileId, "/api/v1/receptions/$id/analyses/$analysisId"))
    }

    private fun completionInput(recordingId: Long, version: Int) = mapOf("recordingFileId" to recordingId, "expectedVersion" to version)

    @Transactional
    fun reserveRecording(adminId: Long, receptionId: Long, key: String, version: Int, metadata: RecordingUploadMetadata, bucket: String): ReceptionCommandResult<RecordingReservation> = execute(adminId, "RESERVE_RECORDING", receptionId, key, mapOf("expectedVersion" to version, "metadata" to metadata), RecordingReservation::class.java) {
        val reserved = recordings.reserve(adminId, receptionId, version, metadata, bucket)
        receptionId to RecordingReservation(reserved.recordingId, receptionId, reserved.receptionVersion)
    }

    @Transactional
    fun renewRecordingUpload(adminId: Long, receptionId: Long, recordingId: Long, key: String): ReceptionCommandResult<RecordingReservation> = execute(adminId, "RENEW_RECORDING_UPLOAD", receptionId, key, mapOf("recordingFileId" to recordingId), RecordingReservation::class.java) {
        val source = recordings.pendingForUpload(adminId, receptionId, recordingId)
        receptionId to RecordingReservation(source.recordingId, receptionId, source.receptionVersion)
    }

    private fun <T : Any> execute(
        adminId: Long,
        operation: String,
        scope: Long,
        key: String,
        request: Any,
        resultType: Class<T>,
        action: () -> Pair<Long, T>,
    ): ReceptionCommandResult<T> {
        validateKey(key)
        if (scope != 0L) ensureOwned(adminId, scope)
        val lockHash = digest("ALLCHECK:RECEPTION:$adminId:$operation:$scope:$key".toByteArray(Charsets.UTF_8))
        val locked = receipts.tryTransactionLock(ByteBuffer.wrap(lockHash).long)
        if (locked != true) throw BusinessException(GlobalErrorCode.OPERATION_IN_PROGRESS)
        val hash = digest(mapper.writeValueAsBytes(request))
        replay(adminId, operation, scope, key, hash, resultType)?.let { return it }
        val (receptionId, value) = action()
        ensureOwned(adminId, receptionId)
        val body = mapper.readValue(mapper.writeValueAsBytes(value), object : TypeReference<Map<String, Any?>>() {})
        receipts.saveAndFlush(ReceptionCommandReceipt(adminId, operation, scope, key, hash, receptionId, body, clock.instant()))
        return ReceptionCommandResult(value, false)
    }

    private fun validateKey(key: String) {
        if (key.isBlank() || key.codePointCount(0, key.length) > 200) throw BusinessException(GlobalErrorCode.INVALID_INPUT_VALUE)
    }

    private fun <T : Any> replay(adminId: Long, operation: String, scope: Long, key: String, hash: ByteArray, resultType: Class<T>): ReceptionCommandResult<T>? {
        val receipt = receipts.findByActorAdminIdAndOperationAndScopeReceptionIdAndIdempotencyKey(adminId, operation, scope, key) ?: return null
        if (!receipt.matches(hash)) throw BusinessException(GlobalErrorCode.IDEMPOTENCY_KEY_REUSED)
        ensureOwned(adminId, receipt.resultReceptionId)
        return ReceptionCommandResult(mapper.convertValue(receipt.responseBody, resultType), true)
    }

    private fun ensureOwned(adminId: Long, receptionId: Long) {
        if (receptions.findByReceptionIdAndOwnerAdminId(receptionId, adminId) == null) throw BusinessException(ReceptionErrorCode.RECEPTION_NOT_FOUND)
    }

    private fun digest(bytes: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(bytes)
}

data class ReceptionCommandResult<T>(val value: T, val replayed: Boolean)

data class RecordingReservation(val recordingId: Long, val receptionId: Long, val version: Int)
