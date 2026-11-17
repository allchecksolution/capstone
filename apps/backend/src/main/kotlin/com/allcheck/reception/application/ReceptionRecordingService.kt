package com.allcheck.reception.application

import com.allcheck.global.exception.BusinessException
import com.allcheck.global.exception.GlobalErrorCode
import com.allcheck.reception.domain.vo.ReceptionExternalId
import com.allcheck.reception.domain.vo.RecordingUploadStatus
import com.allcheck.reception.exception.ReceptionErrorCode
import com.allcheck.reception.infrastructure.storage.RecordingStorageClient
import com.allcheck.reception.presentation.dto.response.RecordingAccessResponse
import com.allcheck.reception.presentation.dto.response.RecordingUploadCompletedResponse
import com.allcheck.reception.presentation.dto.response.RecordingUploadGrantResponse
import org.springframework.beans.factory.annotation.Value
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.orm.ObjectOptimisticLockingFailureException
import org.springframework.stereotype.Service

/** 네트워크 I/O에는 DB 트랜잭션을 열지 않는다. 실제 저장은 별도 프록시의 짧은 트랜잭션에서 수행한다. */
@Service
class ReceptionRecordingService(
    private val transactions: ReceptionRecordingTransactions,
    private val commands: ReceptionCommandService,
    private val storage: RecordingStorageClient,
    @Value("\${reception.storage.bucket:}") private val bucket: String,
) {
    fun upload(adminId: Long, receptionId: Long, key: String, version: Int, metadata: RecordingUploadMetadata): ReceptionCommandResult<RecordingUploadGrantResponse> {
        transactions.ensureOwner(adminId, receptionId)
        if (bucket.isBlank()) throw BusinessException(ReceptionErrorCode.RECORDING_STORAGE_UNAVAILABLE)
        storage.ensureSigningAvailable()
        val result = try {
            commands.reserveRecording(adminId, receptionId, key, version, metadata, bucket)
        } catch (_: ObjectOptimisticLockingFailureException) {
            throw BusinessException(GlobalErrorCode.VERSION_CONFLICT)
        } catch (failure: DataIntegrityViolationException) {
            throw BusinessException(GlobalErrorCode.VERSION_CONFLICT, failure)
        }
        return grant(adminId, result)
    }

    fun renewUpload(adminId: Long, receptionId: Long, recordingId: Long, key: String): ReceptionCommandResult<RecordingUploadGrantResponse> {
        transactions.pendingForUpload(adminId, receptionId, recordingId)
        storage.ensureSigningAvailable()
        return grant(adminId, commands.renewRecordingUpload(adminId, receptionId, recordingId, key))
    }

    private fun grant(adminId: Long, result: ReceptionCommandResult<RecordingReservation>): ReceptionCommandResult<RecordingUploadGrantResponse> {
        val reservation = result.value
        val source = transactions.pendingForUpload(adminId, reservation.receptionId, reservation.recordingId)
        val signed = storage.upload(source.bucket, source.objectKey, source.contentType, source.sizeBytes, source.checksum)
        val headers = signed.requiredHeaders.entries.associate { (name, values) ->
            val canonical = when (name.lowercase()) {
                "content-type" -> "Content-Type"
                "content-length" -> "Content-Length"
                "if-none-match" -> "If-None-Match"
                else -> name.lowercase()
            }
            canonical to values.joinToString(",")
        }
        return ReceptionCommandResult(RecordingUploadGrantResponse(ReceptionExternalId.RECORDING.format(source.recordingId), ReceptionExternalId.RECEPTION.format(source.receptionId), reservation.version, signed.url, headers, signed.expiresAt), result.replayed)
    }

    fun access(adminId: Long, receptionId: Long, recordingId: Long): RecordingAccessResponse {
        val source = transactions.recording(adminId, receptionId, recordingId)
        if (source.status != RecordingUploadStatus.UPLOADED) throw BusinessException(GlobalErrorCode.FILE_NOT_READY)
        val signed = storage.playback(source.bucket, source.objectKey)
        return RecordingAccessResponse(ReceptionExternalId.RECORDING.format(recordingId), signed.url, signed.expiresAt, source.contentType)
    }

    fun complete(adminId: Long, receptionId: Long, recordingId: Long, key: String, version: Int): ReceptionCommandResult<RecordingUploadCompletedResponse> {
        val source = transactions.recording(adminId, receptionId, recordingId)
        commands.completionReplay(adminId, receptionId, recordingId, key, version)?.let { return it }
        val verified = if (source.status == RecordingUploadStatus.UPLOADED) {
            RecordingUploadMetadata("verified-recording", source.contentType, source.sizeBytes, source.checksum)
        } else {
            val actual = storage.inspect(source.bucket, source.objectKey)
            RecordingUploadMetadata("verified-recording", actual.contentType, actual.sizeBytes, actual.checksum)
        }
        return try {
            commands.completeRecording(adminId, receptionId, recordingId, key, version, verified)
        } catch (_: ObjectOptimisticLockingFailureException) {
            resolveConcurrentCompletion(adminId, receptionId, recordingId, key, version, verified)
        } catch (failure: DataIntegrityViolationException) {
            if (transactions.accepted(adminId, receptionId, recordingId) == null) throw failure
            commands.completeRecording(adminId, receptionId, recordingId, key, version, verified)
        }
    }

    private fun resolveConcurrentCompletion(adminId: Long, receptionId: Long, recordingId: Long, key: String, version: Int, verified: RecordingUploadMetadata): ReceptionCommandResult<RecordingUploadCompletedResponse> {
        if (transactions.accepted(adminId, receptionId, recordingId) == null) throw BusinessException(GlobalErrorCode.VERSION_CONFLICT)
        return commands.completeRecording(adminId, receptionId, recordingId, key, version, verified)
    }
}
