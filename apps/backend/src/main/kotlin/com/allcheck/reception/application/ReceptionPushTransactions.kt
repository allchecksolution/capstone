package com.allcheck.reception.application

import com.allcheck.reception.domain.entity.ReceptionAnalysisEvent
import com.allcheck.reception.domain.entity.ReceptionPushDelivery
import com.allcheck.reception.domain.vo.ReceptionAnalysisStatus
import com.allcheck.reception.infrastructure.repository.ReceptionAnalysisEventRepository
import com.allcheck.reception.infrastructure.repository.ReceptionAnalysisRepository
import com.allcheck.reception.infrastructure.repository.ReceptionPushDeliveryRepository
import com.allcheck.reception.infrastructure.repository.ReceptionRepository
import org.springframework.beans.factory.annotation.Value
import org.springframework.data.domain.PageRequest
import org.springframework.stereotype.Component
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.Duration

@Component
class ReceptionPushPolicy(
    @Value("\${reception.push.max-attempts}") val maxAttempts: Int,
    @Value("\${reception.push.retry-delay}") val retryDelay: Duration,
) {
    init {
        require(maxAttempts in 1..10 && retryDelay >= Duration.ofMinutes(1)) { "Push retries require 1-to-10 total attempts and at least one minute base delay." }
    }

    fun delay(attempt: Int, requested: Duration?): Duration {
        require(attempt in 1..10)
        val backoff = retryDelay.multipliedBy(1L shl (attempt - 1))
        return maxOf(backoff, requested ?: Duration.ZERO)
    }
}

@Service
@Transactional(readOnly = true)
class ReceptionPushTransactions(
    private val events: ReceptionAnalysisEventRepository,
    private val deliveries: ReceptionPushDeliveryRepository,
    private val receptions: ReceptionRepository,
    private val analyses: ReceptionAnalysisRepository,
    private val policy: ReceptionPushPolicy,
    private val clock: Clock,
) {
    fun pendingEvents(limit: Int): List<PendingPushEvent> = events.findAllByStatusOrderByCreatedAtAscEventIdAsc("PENDING", PageRequest.of(0, limit))
        .map { PendingPushEvent(checkNotNull(it.eventId), it.recipientAdminId) }

    @Transactional
    fun expand(eventId: Long, installations: List<String>) {
        val event = events.findById(eventId).orElseThrow()
        if (event.status != "PENDING") return
        val targets = if (isCurrent(event)) installations.distinct() else emptyList()
        val now = clock.instant()
        targets.forEach { deliveries.save(ReceptionPushDelivery(eventId, it, now)) }
        event.finishExpansion(targets.isNotEmpty(), now)
        events.flush()
    }

    fun due(limit: Int): List<Long> = deliveries.findAllByStatusAndNextAttemptAtLessThanEqualOrderByNextAttemptAtAscDeliveryIdAsc("PENDING", clock.instant(), PageRequest.of(0, limit)).map { checkNotNull(it.deliveryId) }

    fun overdue(limit: Int): List<Long> = deliveries.findAllByStatusAndLeaseExpiresAtLessThanEqualOrderByLeaseExpiresAtAscDeliveryIdAsc("PROCESSING", clock.instant(), PageRequest.of(0, limit)).map { checkNotNull(it.deliveryId) }

    @Transactional
    fun claim(deliveryId: Long, worker: String, ttl: Duration): PushTask? {
        val delivery = deliveries.findById(deliveryId).orElseThrow()
        val now = clock.instant()
        if (!delivery.claim(worker, now, ttl)) return null
        val event = events.findById(delivery.eventId).orElseThrow()
        if (!isCurrent(event)) {
            delivery.skip(worker, now)
            deliveries.flush()
            return null
        }
        deliveries.flush()
        return PushTask(deliveryId, delivery.eventId, event.recipientAdminId, delivery.installationId, event.receptionId, event.analysisRequestId, event.eventType, worker)
    }

    @Transactional
    fun prepareSend(task: PushTask): Boolean {
        val delivery = deliveries.findById(task.deliveryId).orElseThrow()
        if (delivery.status != "PROCESSING" || delivery.claimedBy != task.worker) return false
        val now = clock.instant()
        if (!now.isBefore(checkNotNull(delivery.leaseExpiresAt))) {
            failDelivery(delivery, task.worker, "REMOTE_OUTCOME_UNKNOWN", true, null)
            return false
        }
        if (!isCurrent(events.findById(task.eventId).orElseThrow())) {
            delivery.skip(task.worker, now)
            deliveries.flush()
            return false
        }
        return true
    }

    @Transactional
    fun accepted(task: PushTask, providerId: String) {
        val delivery = deliveries.findById(task.deliveryId).orElseThrow()
        if (delivery.status != "PROCESSING" || delivery.claimedBy != task.worker) return
        if (!delivery.accepted(task.worker, providerId, clock.instant())) failDelivery(delivery, task.worker, "REMOTE_OUTCOME_UNKNOWN", true, null)
        deliveries.flush()
    }

    @Transactional
    fun failed(task: PushTask, code: String, retryable: Boolean, retryAfter: Duration?) {
        failDelivery(deliveries.findById(task.deliveryId).orElseThrow(), task.worker, code, retryable, retryAfter)
    }

    @Transactional
    fun skip(task: PushTask) {
        deliveries.findById(task.deliveryId).orElseThrow().skip(task.worker, clock.instant())
        deliveries.flush()
    }

    @Transactional
    fun expire(deliveryId: Long) {
        val delivery = deliveries.findById(deliveryId).orElseThrow()
        if (delivery.status != "PROCESSING" || clock.instant().isBefore(checkNotNull(delivery.leaseExpiresAt))) return
        failDelivery(delivery, checkNotNull(delivery.claimedBy), "REMOTE_OUTCOME_UNKNOWN", true, null)
    }

    private fun failDelivery(delivery: ReceptionPushDelivery, worker: String, code: String, retryable: Boolean, retryAfter: Duration?) {
        if (delivery.status != "PROCESSING" || delivery.claimedBy != worker) return
        val now = clock.instant()
        val retryAt = now.plus(policy.delay(delivery.attemptCount, retryAfter))
        delivery.fail(worker, code, retryable, retryAt, policy.maxAttempts, now)
        deliveries.flush()
    }

    private fun isCurrent(event: ReceptionAnalysisEvent): Boolean {
        val reception = receptions.findByReceptionIdAndOwnerAdminId(event.receptionId, event.recipientAdminId) ?: return false
        if (reception.latestAnalysisRequestId != event.analysisRequestId) return false
        val analysis = analyses.findByAnalysisRequestIdAndReceptionId(event.analysisRequestId, event.receptionId) ?: return false
        val expectedType = if (analysis.status == ReceptionAnalysisStatus.SUCCEEDED) "RECEPTION_ANALYSIS_SUCCEEDED" else "RECEPTION_ANALYSIS_FAILED"
        return analysis.requestedByAdminId == event.recipientAdminId && analysis.recordingFileId == reception.recordingFileId && event.eventType == expectedType && analysis.status in setOf(ReceptionAnalysisStatus.SUCCEEDED, ReceptionAnalysisStatus.FAILED, ReceptionAnalysisStatus.DISCARDED)
    }
}

data class PendingPushEvent(val eventId: Long, val adminId: Long)
data class PushTask(val deliveryId: Long, val eventId: Long, val adminId: Long, val installationId: String, val receptionId: Long, val analysisId: Long, val eventType: String, val worker: String)
