package com.allcheck.reception.application

import com.allcheck.reception.domain.entity.ReceptionAnalysis
import com.allcheck.reception.domain.entity.ReceptionAnalysisEvent
import com.allcheck.reception.domain.entity.ReceptionTranscript
import com.allcheck.reception.domain.vo.ReceptionAnalysisFailureReason
import com.allcheck.reception.domain.vo.ReceptionAnalysisStatus
import com.allcheck.reception.domain.vo.ReceptionAnalysisTransition
import com.allcheck.reception.domain.vo.ReceptionStructuredResult
import com.allcheck.reception.infrastructure.repository.ReceptionAnalysisEventRepository
import com.allcheck.reception.infrastructure.repository.ReceptionAnalysisRepository
import com.allcheck.reception.infrastructure.repository.ReceptionRepository
import com.allcheck.reception.infrastructure.repository.ReceptionTranscriptRepository
import org.springframework.data.domain.PageRequest
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.Duration
import java.time.Instant

/** 외부 I/O를 호출하지 않는 분석 체크포인트. 호출자는 각 메서드가 커밋된 뒤 다음 단계를 실행한다. */
@Service
@Transactional(readOnly = true)
class ReceptionAnalysisTransactions(
    private val analyses: ReceptionAnalysisRepository,
    private val receptions: ReceptionRepository,
    private val transcripts: ReceptionTranscriptRepository,
    private val events: ReceptionAnalysisEventRepository,
    private val clock: Clock,
) {
    fun pending(limit: Int): List<Long> {
        require(limit > 0)
        return analyses.findAllByStatusAndIsInitialTrueOrderByCreatedAtAscAnalysisRequestIdAsc(ReceptionAnalysisStatus.PENDING, PageRequest.of(0, limit))
            .map { checkNotNull(it.analysisRequestId) }
    }

    @Transactional
    fun claim(analysisId: Long, workerId: String, budget: Duration): AnalysisTask? {
        require(!budget.isNegative && !budget.isZero)
        val analysis = analysis(analysisId)
        val now = clock.instant()
        if (!analysis.claim(workerId, now, now.plus(budget))) return null
        analyses.flush()
        return AnalysisTask.from(analysis)
    }

    @Transactional
    fun transcribed(analysisId: Long, workerId: String, transcript: String, traceId: String): ReceptionAnalysisTransition {
        val analysis = analysis(analysisId)
        val before = analysis.status
        val now = clock.instant()
        val result = analysis.receiveTranscription(workerId, transcript, traceId, now)
        completeChange(analysis, before, now)
        return result
    }

    @Transactional
    fun storeTranscript(analysisId: Long, workerId: String, transcript: String, traceId: String): ReceptionAnalysisTransition {
        val analysis = analysis(analysisId)
        val before = analysis.status
        val now = clock.instant()
        val readiness = analysis.prepareTranscriptStorage(workerId, traceId, now)
        if (readiness != ReceptionAnalysisTransition.ADVANCED) {
            completeChange(analysis, before, now)
            return readiness
        }
        val original = transcripts.saveAndFlush(
            ReceptionTranscript(analysis.receptionId, analysis.recordingFileId, analysisId, transcript, now),
        )
        val attachedAt = clock.instant()
        val result = analysis.attachTranscript(workerId, original, traceId, attachedAt)
        if (result != ReceptionAnalysisTransition.ADVANCED) throw TranscriptAcceptanceLost(result)
        val reception = receptions.findById(analysis.receptionId).orElseThrow()
        reception.attachCanonicalTranscript(analysis, original, attachedAt)
        completeChange(analysis, before, attachedAt)
        return result
    }

    @Transactional
    fun startStructuring(analysisId: Long, workerId: String, traceId: String): ReceptionAnalysisTransition {
        val analysis = analysis(analysisId)
        val before = analysis.status
        val now = clock.instant()
        val result = analysis.startStructuring(workerId, traceId, now)
        completeChange(analysis, before, now)
        return result
    }

    @Transactional
    fun structured(analysisId: Long, workerId: String, result: ReceptionStructuredResult, traceId: String): ReceptionAnalysisTransition {
        val analysis = analysis(analysisId)
        val before = analysis.status
        val now = clock.instant()
        val transition = analysis.receiveStructuredResult(workerId, result, traceId, now)
        completeChange(analysis, before, now)
        return transition
    }

    @Transactional
    fun apply(analysisId: Long, workerId: String, traceId: String): ReceptionAnalysisTransition {
        val analysis = analysis(analysisId)
        val before = analysis.status
        val reception = receptions.findById(analysis.receptionId).orElseThrow()
        val now = clock.instant()
        val result = analysis.applyResultTo(reception, workerId, traceId, now)
        completeChange(analysis, before, now)
        return result
    }

    @Transactional
    fun failStt(analysisId: Long, workerId: String, reason: ReceptionAnalysisFailureReason, traceId: String) {
        val analysis = analysis(analysisId)
        val before = analysis.status
        val now = clock.instant()
        analysis.failTranscription(workerId, reason, traceId, now)
        completeChange(analysis, before, now)
    }

    @Transactional
    fun failStorage(analysisId: Long, workerId: String, traceId: String) {
        val analysis = analysis(analysisId)
        val before = analysis.status
        val now = clock.instant()
        analysis.failTranscriptStorage(workerId, traceId, now)
        completeChange(analysis, before, now)
    }

    @Transactional
    fun failStructure(analysisId: Long, workerId: String, reason: ReceptionAnalysisFailureReason, traceId: String) {
        val analysis = analysis(analysisId)
        val before = analysis.status
        val now = clock.instant()
        analysis.failStructuring(workerId, reason, traceId, now)
        completeChange(analysis, before, now)
    }

    @Transactional
    fun expire(analysisId: Long, traceId: String): Boolean {
        val analysis = analysis(analysisId)
        val before = analysis.status
        val now = clock.instant()
        val expired = analysis.expireIfDue(now, traceId)
        completeChange(analysis, before, now)
        return expired
    }

    private fun analysis(id: Long) = analyses.findById(id).orElseThrow()

    private fun completeChange(analysis: ReceptionAnalysis, before: ReceptionAnalysisStatus, now: Instant) {
        if (before != analysis.status && analysis.status in setOf(ReceptionAnalysisStatus.SUCCEEDED, ReceptionAnalysisStatus.FAILED, ReceptionAnalysisStatus.DISCARDED)) {
            events.save(ReceptionAnalysisEvent.completed(analysis, now))
        }
        analyses.flush()
    }
}

data class AnalysisTask(val analysisId: Long, val receptionId: Long, val recordingId: Long, val receptionVersion: Int, val adminId: Long, val referenceTime: Instant) {
    companion object {
        fun from(analysis: ReceptionAnalysis) = AnalysisTask(
            checkNotNull(analysis.analysisRequestId),
            analysis.receptionId,
            analysis.recordingFileId,
            analysis.receptionVersion,
            analysis.requestedByAdminId,
            analysis.referenceTime,
        )
    }
}

/** INSERT까지 수행한 원문을 반드시 롤백한 뒤, 바깥에서 만료/종료 상태를 다시 기록하도록 전달한다. */
class TranscriptAcceptanceLost(val transition: ReceptionAnalysisTransition) : RuntimeException("Transcript attachment was not accepted.")
