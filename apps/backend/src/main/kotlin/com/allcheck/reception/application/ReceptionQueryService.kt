package com.allcheck.reception.application

import com.allcheck.global.exception.BusinessException
import com.allcheck.global.exception.GlobalErrorCode
import com.allcheck.reception.domain.entity.Reception
import com.allcheck.reception.domain.entity.ReceptionAnalysis
import com.allcheck.reception.domain.entity.ReceptionRecording
import com.allcheck.reception.domain.entity.ReceptionTranscript
import com.allcheck.reception.domain.vo.ReceptionAnalysisStatus
import com.allcheck.reception.domain.vo.ReceptionExternalId
import com.allcheck.reception.domain.vo.ReceptionProcessingStatus
import com.allcheck.reception.domain.vo.ReceptionReviewStatus
import com.allcheck.reception.domain.vo.RecordingUploadStatus
import com.allcheck.reception.exception.ReceptionErrorCode
import com.allcheck.reception.infrastructure.repository.ReceptionAnalysisRepository
import com.allcheck.reception.infrastructure.repository.ReceptionRecordingRepository
import com.allcheck.reception.infrastructure.repository.ReceptionRepository
import com.allcheck.reception.infrastructure.repository.ReceptionTranscriptRepository
import com.allcheck.reception.presentation.dto.response.ReceptionAiOriginalResponse
import com.allcheck.reception.presentation.dto.response.ReceptionAnalysisError
import com.allcheck.reception.presentation.dto.response.ReceptionAnalysisResponse
import com.allcheck.reception.presentation.dto.response.ReceptionDetailResponse
import com.allcheck.reception.presentation.dto.response.ReceptionEditableResponse
import com.allcheck.reception.presentation.dto.response.ReceptionListResponse
import com.allcheck.reception.presentation.dto.response.ReceptionPageInfo
import com.allcheck.reception.presentation.dto.response.ReceptionSummaryResponse
import org.springframework.data.domain.PageRequest
import org.springframework.data.domain.Sort
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Isolation
import org.springframework.transaction.annotation.Transactional
import java.time.ZoneId

/** 여러 Aggregate의 원본·진행 상태를 같은 DB 스냅샷에서 읽는다. 조회는 상태를 변경하지 않는다. */
@Service
@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
class ReceptionQueryService(
    private val receptions: ReceptionRepository,
    private val recordings: ReceptionRecordingRepository,
    private val analyses: ReceptionAnalysisRepository,
    private val transcripts: ReceptionTranscriptRepository,
) {
    fun detail(adminId: Long, receptionId: Long): ReceptionDetailResponse {
        val reception = owned(adminId, receptionId)
        val material = loadMaterials(listOf(reception)).getValue(receptionId)
        return ReceptionDetailResponse(
            receptionId = ReceptionExternalId.RECEPTION.format(receptionId),
            receivedAt = reception.receivedAt,
            callerPhoneMasked = mask(reception.callerPhone ?: reception.recordingContactPhone),
            reviewStatus = reception.reviewStatus,
            recordingStatus = material.recordingStatus,
            sttStatus = material.sttStatus,
            transcriptStorageStatus = material.storageStatus,
            analysisStatus = material.analysisStatus(reception),
            version = reception.version,
            recordingFileId = reception.recordingFileId?.let(ReceptionExternalId.RECORDING::format),
            transcriptFileId = reception.transcriptFileId?.let(ReceptionExternalId.TRANSCRIPT::format),
            latestAnalysisRequestId = reception.latestAnalysisRequestId?.let(ReceptionExternalId.ANALYSIS::format),
            recordingReceivedAt = material.recording?.recordingReceivedAt?.atZone(SEOUL)?.toOffsetDateTime(),
            referenceTime = material.analysis?.referenceTime?.atZone(SEOUL)?.toOffsetDateTime(),
            timeZone = SEOUL.id,
            transcript = material.transcript?.transcript,
            aiOriginal = reception.aiOriginal?.let(ReceptionAiOriginalResponse::from),
            finalData = ReceptionEditableResponse.from(reception.editableData),
            confirmedAt = reception.confirmedAt,
            requestId = reception.requestId?.let(ReceptionExternalId.REQUEST::format),
            reviewEditable = reception.reviewStatus == ReceptionReviewStatus.DRAFT &&
                reception.requestId == null &&
                material.analysis?.status !in setOf(ReceptionAnalysisStatus.PENDING, ReceptionAnalysisStatus.PROCESSING) &&
                (reception.recordingFileId == null || material.analysis != null),
        )
    }

    fun list(adminId: Long, reviewStatus: ReceptionReviewStatus?, page: Int, size: Int): ReceptionListResponse {
        if (page < 1 || size !in 1..100) throw BusinessException(GlobalErrorCode.INVALID_INPUT_VALUE)
        val pageable = PageRequest.of(page - 1, size, Sort.by(Sort.Direction.DESC, "receivedAt", "receptionId"))
        val found = if (reviewStatus == null) {
            receptions.findAllByOwnerAdminId(adminId, pageable)
        } else {
            receptions.findAllByOwnerAdminIdAndReviewStatus(adminId, reviewStatus, pageable)
        }
        val materials = loadMaterials(found.content)
        val items = found.content.map { reception ->
            val id = checkNotNull(reception.receptionId)
            val material = materials.getValue(id)
            ReceptionSummaryResponse(
                ReceptionExternalId.RECEPTION.format(id),
                reception.receivedAt,
                mask(reception.callerPhone ?: reception.recordingContactPhone),
                reception.reviewStatus,
                material.recordingStatus,
                material.sttStatus,
                material.analysisStatus(reception),
                reception.version,
            )
        }
        return ReceptionListResponse(items, ReceptionPageInfo(page, size, found.totalElements, found.totalPages))
    }

    fun analysis(adminId: Long, receptionId: Long, analysisId: Long): ReceptionAnalysisResponse {
        owned(adminId, receptionId)
        val analysis = analyses.findByAnalysisRequestIdAndReceptionId(analysisId, receptionId)
            ?: throw BusinessException(ReceptionErrorCode.RECEPTION_NOT_FOUND)
        check(analysis.requestedByAdminId == adminId) { "The analysis owner must match the reception owner." }
        val error = analysis.errorCode?.let { ReceptionAnalysisError(it, checkNotNull(analysis.errorMessage), checkNotNull(analysis.traceId)) }
        return ReceptionAnalysisResponse(
            ReceptionExternalId.ANALYSIS.format(analysisId), ReceptionExternalId.RECEPTION.format(receptionId),
            analysis.receptionVersion, ReceptionExternalId.RECORDING.format(analysis.recordingFileId), analysis.mode,
            analysis.status, analysis.stage, analysis.sttStatus, analysis.transcriptStorageStatus, analysis.analysisStatus,
            analysis.transcriptFileId?.let(ReceptionExternalId.TRANSCRIPT::format), analysis.resultApplied,
            analysis.createdAt, analysis.completedAt, error, analysis.aiOriginal?.let(ReceptionAiOriginalResponse::from), analysis.missingFields,
        )
    }

    private fun loadMaterials(roots: List<Reception>): Map<Long, Materials> {
        val recordingsById = recordings.findAllById(roots.mapNotNull { it.recordingFileId }).associateBy { checkNotNull(it.recordingFileId) }
        val analysesById = analyses.findAllById(roots.mapNotNull { it.latestAnalysisRequestId }).associateBy { checkNotNull(it.analysisRequestId) }
        val transcriptsById = transcripts.findAllById(roots.mapNotNull { it.transcriptFileId }).associateBy { checkNotNull(it.transcriptFileId) }
        return roots.associate { root ->
            val recording = root.recordingFileId?.let { checkNotNull(recordingsById[it]) { "Referenced recording must exist." } }
            val analysis = root.latestAnalysisRequestId?.let { checkNotNull(analysesById[it]) { "Referenced analysis must exist." } }
            val transcript = root.transcriptFileId?.let { checkNotNull(transcriptsById[it]) { "Referenced transcript must exist." } }
            check(recording == null || recording.receptionId == root.receptionId && recording.uploadedByAdminId == root.ownerAdminId) { "Recording source must match the reception." }
            check(analysis == null || analysis.receptionId == root.receptionId && analysis.requestedByAdminId == root.ownerAdminId && analysis.recordingFileId == root.recordingFileId) { "Analysis source must match the reception." }
            check(transcript == null || transcript.receptionId == root.receptionId && transcript.recordingFileId == root.recordingFileId) { "Transcript source must match the reception." }
            checkNotNull(root.receptionId) to Materials(recording, analysis, transcript)
        }
    }

    private fun owned(adminId: Long, receptionId: Long) = receptions.findByReceptionIdAndOwnerAdminId(receptionId, adminId)
        ?: throw BusinessException(ReceptionErrorCode.RECEPTION_NOT_FOUND)

    private fun mask(phone: String?): String? {
        if (phone == null) return null
        var digitsToHide = (phone.count { it in '0'..'9' } - 4).coerceAtLeast(0)
        return phone.map { if (it in '0'..'9' && digitsToHide-- > 0) '*' else it }.joinToString("")
    }

    private data class Materials(val recording: ReceptionRecording?, val analysis: ReceptionAnalysis?, val transcript: ReceptionTranscript?) {
        val recordingStatus: ReceptionProcessingStatus get() = when (recording?.status) {
            null -> ReceptionProcessingStatus.NOT_AVAILABLE
            RecordingUploadStatus.PENDING -> ReceptionProcessingStatus.PENDING
            RecordingUploadStatus.UPLOADED -> ReceptionProcessingStatus.SUCCEEDED
        }
        val sttStatus: ReceptionProcessingStatus get() = if (transcript != null) ReceptionProcessingStatus.SUCCEEDED else analysis?.sttStatus ?: ReceptionProcessingStatus.NOT_AVAILABLE
        val storageStatus: ReceptionProcessingStatus get() = if (transcript != null) {
            ReceptionProcessingStatus.SUCCEEDED
        } else {
            analysis?.transcriptStorageStatus?.takeUnless { it == ReceptionProcessingStatus.SUCCEEDED } ?: ReceptionProcessingStatus.NOT_AVAILABLE
        }

        fun analysisStatus(root: Reception): ReceptionProcessingStatus = when {
            root.aiOriginal != null -> ReceptionProcessingStatus.SUCCEEDED
            analysis?.status == ReceptionAnalysisStatus.DISCARDED -> ReceptionProcessingStatus.NOT_AVAILABLE
            else -> analysis?.analysisStatus ?: ReceptionProcessingStatus.NOT_AVAILABLE
        }
    }

    private companion object {
        val SEOUL: ZoneId = ZoneId.of("Asia/Seoul")
    }
}
