package com.allcheck.reception.application.analysis

import com.allcheck.global.exception.BusinessException
import com.allcheck.reception.application.recording.ReceptionRecordingTransactions
import com.allcheck.reception.domain.vo.ReceptionExternalId
import com.allcheck.reception.domain.vo.analysis.ReceptionAnalysisFailureReason
import com.allcheck.reception.domain.vo.analysis.ReceptionAnalysisTransition
import com.allcheck.reception.infrastructure.ai.AiAnalyzeRequest
import com.allcheck.reception.infrastructure.ai.AiCallException
import com.allcheck.reception.infrastructure.ai.AiClient
import com.allcheck.reception.infrastructure.ai.AiFailureKind
import com.allcheck.reception.infrastructure.ai.AiFileChecksum
import com.allcheck.reception.infrastructure.ai.AiRecordingSource
import com.allcheck.reception.infrastructure.ai.AiTranscribeRequest
import com.allcheck.reception.infrastructure.storage.RecordingStorageClient
import org.springframework.dao.DataAccessException
import org.springframework.orm.ObjectOptimisticLockingFailureException
import org.springframework.stereotype.Service
import org.springframework.transaction.TransactionException
import java.time.Duration
import java.time.ZoneOffset
import java.util.Base64
import java.util.UUID

/** 각 체크포인트는 별도 트랜잭션이며 AI·S3 호출 중에는 DB 트랜잭션을 보유하지 않는다. */
@Service
class ReceptionAnalysisService(
    private val transactions: ReceptionAnalysisTransactions,
    private val recordings: ReceptionRecordingTransactions,
    private val storage: RecordingStorageClient,
    private val ai: AiClient,
) {
    fun execute(analysisId: Long, budget: Duration) {
        val worker = UUID.randomUUID().toString()
        val trace = UUID.randomUUID().toString()
        val task = try {
            transactions.claim(analysisId, worker, budget)
        } catch (_: ObjectOptimisticLockingFailureException) {
            return // 다른 작업자의 커밋이 실행권을 확보했다. 외부 호출은 수행하지 않는다.
        } ?: return
        val transcript = transcribe(task, worker, trace) ?: return
        if (transactions.transcribed(analysisId, worker, transcript, trace) != ReceptionAnalysisTransition.ADVANCED) return
        if (!store(task, worker, transcript, trace)) return
        if (transactions.startStructuring(analysisId, worker, trace) != ReceptionAnalysisTransition.ADVANCED) return
        val original = transactions.storedTranscript(task)
        val request = AiAnalyzeRequest(
            ReceptionExternalId.ANALYSIS.format(task.analysisId),
            ReceptionExternalId.RECEPTION.format(task.receptionId),
            task.receptionVersion,
            ReceptionExternalId.RECORDING.format(task.recordingId),
            ReceptionExternalId.TRANSCRIPT.format(original.transcriptId),
            original.text,
            task.referenceTime.atOffset(SEOUL_OFFSET),
        )
        val result = try {
            ai.analyze(request)
        } catch (failure: AiCallException) {
            transactions.failStructure(analysisId, worker, failure.reason(ReceptionAnalysisFailureReason.AI_STRUCTURING_FAILED), trace)
            return
        }
        if (transactions.structured(analysisId, worker, result, trace) != ReceptionAnalysisTransition.ADVANCED) return
        try {
            transactions.apply(analysisId, worker, trace)
        } catch (_: ObjectOptimisticLockingFailureException) {
            // 저장된 결과만 다시 판정한다. AI 호출은 반복하지 않으며 현재 상태에 따라 IGNORED/DISCARDED가 된다.
            transactions.apply(analysisId, worker, trace)
        }
    }

    private fun transcribe(task: AnalysisTask, worker: String, trace: String): String? = try {
        val source = recordings.analysisRecording(task)
        val signed = storage.aiDownload(source.bucket, source.objectKey)
        ai.transcribe(
            AiTranscribeRequest(
                ReceptionExternalId.ANALYSIS.format(task.analysisId),
                ReceptionExternalId.RECEPTION.format(task.receptionId),
                task.receptionVersion,
                ReceptionExternalId.RECORDING.format(task.recordingId),
                AiRecordingSource(signed.url, signed.expiresAt.atOffset(SEOUL_OFFSET), source.contentType, source.sizeBytes, AiFileChecksum(Base64.getEncoder().encodeToString(source.checksum))),
            ),
        )
    } catch (failure: AiCallException) {
        transactions.failStt(task.analysisId, worker, failure.reason(ReceptionAnalysisFailureReason.AI_STT_FAILED), trace)
        null
    } catch (_: BusinessException) {
        transactions.failStt(task.analysisId, worker, ReceptionAnalysisFailureReason.AI_STT_FAILED, trace)
        null
    }

    private fun store(task: AnalysisTask, worker: String, transcript: String, trace: String): Boolean = try {
        transactions.storeTranscript(task.analysisId, worker, transcript, trace) == ReceptionAnalysisTransition.ADVANCED
    } catch (_: TranscriptAcceptanceLost) {
        transactions.expire(task.analysisId, trace)
        false
    } catch (_: DataAccessException) {
        transactions.failStorage(task.analysisId, worker, trace)
        false
    } catch (_: TransactionException) {
        transactions.failStorage(task.analysisId, worker, trace)
        false
    }

    private fun AiCallException.reason(fallback: ReceptionAnalysisFailureReason) = if (kind == AiFailureKind.TIMEOUT) ReceptionAnalysisFailureReason.AI_TIMEOUT else fallback

    private companion object {
        val SEOUL_OFFSET: ZoneOffset = ZoneOffset.ofHours(9)
    }
}
