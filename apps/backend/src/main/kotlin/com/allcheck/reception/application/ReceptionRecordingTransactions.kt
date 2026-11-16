package com.allcheck.reception.application

import com.allcheck.global.exception.BusinessException
import com.allcheck.global.exception.GlobalErrorCode
import com.allcheck.reception.domain.entity.ReceptionAnalysis
import com.allcheck.reception.domain.entity.ReceptionRecording
import com.allcheck.reception.exception.ReceptionErrorCode
import com.allcheck.reception.infrastructure.repository.ReceptionAnalysisRepository
import com.allcheck.reception.infrastructure.repository.ReceptionRecordingRepository
import com.allcheck.reception.infrastructure.repository.ReceptionRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID

@Service
@Transactional(readOnly = true)
class ReceptionRecordingTransactions(
    private val receptions: ReceptionRepository,
    private val recordings: ReceptionRecordingRepository,
    private val analyses: ReceptionAnalysisRepository,
    private val clock: Clock,
) {
    @Transactional
    fun reserve(adminId: Long, receptionId: Long, expectedVersion: Int, metadata: RecordingUploadMetadata, bucket: String): RecordingHandle {
        val reception = owned(adminId, receptionId)
        if (reception.version != expectedVersion) throw BusinessException(GlobalErrorCode.VERSION_CONFLICT)
        if (reception.recordingFileId != null) throw BusinessException(ReceptionErrorCode.RECORDING_ALREADY_EXISTS)
        val now = clock.instant().truncatedTo(ChronoUnit.MICROS)
        val recording = recordings.saveAndFlush(
            ReceptionRecording(receptionId, adminId, metadata.originalFilename, bucket, "recordings/$receptionId/${UUID.randomUUID()}", metadata.contentType, metadata.sizeBytes, metadata.checksum, now),
        )
        reception.reserveRecording(expectedVersion, checkNotNull(recording.recordingFileId), now)
        receptions.flush()
        return RecordingHandle.from(recording, reception.version)
    }

    fun recording(adminId: Long, receptionId: Long, recordingId: Long): RecordingHandle {
        val reception = owned(adminId, receptionId)
        if (reception.recordingFileId != recordingId) throw BusinessException(ReceptionErrorCode.RECEPTION_NOT_FOUND)
        val recording = recordings.findByRecordingFileIdAndReceptionIdAndUploadedByAdminId(recordingId, receptionId, adminId)
            ?: throw BusinessException(ReceptionErrorCode.RECEPTION_NOT_FOUND)
        return RecordingHandle.from(recording, reception.version)
    }

    /** S3가 반환한 실제 값은 외부 I/O를 마친 호출자가 전달한다. 이 트랜잭션 안에서는 S3를 호출하지 않는다. */
    @Transactional
    fun complete(adminId: Long, receptionId: Long, recordingId: Long, expectedVersion: Int, verified: RecordingUploadMetadata): RecordingAcceptance {
        val reception = owned(adminId, receptionId)
        if (reception.recordingFileId != recordingId) throw BusinessException(ReceptionErrorCode.RECEPTION_NOT_FOUND)
        val recording = recordings.findByRecordingFileIdAndReceptionIdAndUploadedByAdminId(recordingId, receptionId, adminId)
            ?: throw BusinessException(ReceptionErrorCode.RECEPTION_NOT_FOUND)
        analyses.findByRecordingFileIdAndIsInitialTrue(recordingId)?.let {
            check(it.receptionId == receptionId && it.requestedByAdminId == adminId && it.referenceTime == recording.recordingReceivedAt) { "The original acceptance must match the recording source." }
            return RecordingAcceptance.from(it)
        }
        if (reception.version != expectedVersion) throw BusinessException(GlobalErrorCode.VERSION_CONFLICT)
        val now = clock.instant().truncatedTo(ChronoUnit.MICROS)
        recording.completeUpload(verified.contentType, verified.sizeBytes, verified.checksum, now)
        recordings.flush()
        val analysis = analyses.saveAndFlush(
            ReceptionAnalysis(receptionId, recordingId, Math.incrementExact(reception.version), adminId, checkNotNull(recording.recordingReceivedAt), now),
        )
        reception.acceptInitialAnalysis(expectedVersion, recording, analysis, now)
        receptions.flush()
        return RecordingAcceptance.from(analysis)
    }

    private fun owned(adminId: Long, receptionId: Long) = receptions.findByReceptionIdAndOwnerAdminId(receptionId, adminId)
        ?: throw BusinessException(ReceptionErrorCode.RECEPTION_NOT_FOUND)
}

data class RecordingAcceptance(val analysisId: Long, val receptionId: Long, val receptionVersion: Int, val recordingId: Long, val referenceTime: Instant) {
    companion object {
        fun from(analysis: ReceptionAnalysis) = RecordingAcceptance(
            checkNotNull(analysis.analysisRequestId),
            analysis.receptionId,
            analysis.receptionVersion,
            analysis.recordingFileId,
            analysis.referenceTime,
        )
    }
}
