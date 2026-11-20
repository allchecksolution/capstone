package com.allcheck.operations.coordination.application

import com.allcheck.operations.ActorRole
import com.allcheck.operations.CandidateContext
import com.allcheck.operations.DeliveryStatus
import com.allcheck.operations.DriverCandidate
import com.allcheck.operations.FollowUpTarget
import com.allcheck.operations.OfferStatus
import com.allcheck.operations.OperationsActor
import com.allcheck.operations.RequestStatus
import com.allcheck.operations.RoundStatus
import com.allcheck.operations.dispatch.DispatchOperations
import com.allcheck.operations.domain.entity.OperationsTask
import com.allcheck.operations.domain.entity.TaskKind
import com.allcheck.operations.domain.entity.TaskState
import com.allcheck.operations.infrastructure.geo.OperationsSpatial
import com.allcheck.operations.infrastructure.repository.OperationsTaskRepository
import com.allcheck.operations.request.RequestOperations
import com.allcheck.operations.work.WorkOperations
import jakarta.persistence.EntityManager
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Duration

data class TaskClaim(val id: Long, val kind: TaskKind, val requestId: Long, val subjectId: Long, val logicalKey: String, val token: String, val attempts: Int, val actor: OperationsActor?, val installation: String?)
data class PushRecipient(val actor: OperationsActor, val installation: String)

@Service
@Transactional(readOnly = true)
class OperationsTaskTransactions(
    private val tasks: OperationsTaskRepository,
    private val requests: RequestOperations,
    private val dispatch: DispatchOperations,
    private val works: WorkOperations,
    private val spatial: OperationsSpatial,
    private val commands: OperationsCommands,
    private val em: EntityManager,
    @Value("\${operations.worker.lease:60s}") private val lease: Duration,
    @Value("\${operations.dispatch.offer-lifetime:1m}") private val offerLifetime: Duration,
    @Value("\${operations.push.admin-max-attempts:3}") private val adminMaxAttempts: Int,
    @Value("\${operations.push.admin-retry-delay:1m}") private val adminRetryDelay: Duration,
) {
    init {
        require(lease.isPositive && offerLifetime.isPositive && adminMaxAttempts > 0 && adminRetryDelay.isPositive)
    }

    @Transactional
    fun claim(): TaskClaim? {
        val now = commands.now()
        val task = tasks.claimable(now) ?: return null
        val token = task.claim(now, lease)
        return TaskClaim(checkNotNull(task.taskId), task.kind, task.requestId, task.subjectId, task.logicalKey, token, task.attempts, task.actorRole?.let { OperationsActor(it, checkNotNull(task.actorId)) }, task.installationId)
    }
    fun searchContext(claim: TaskClaim): CandidateContext {
        val request = requests.get(claim.requestId)
        return CandidateContext(request.intake.workType, request.site, dispatch.round(claim.subjectId).radiusKm, commands.now())
    }
    fun valid(claim: TaskClaim): Boolean {
        val task = tasks.findById(claim.id).orElse(null) ?: return false
        return task.held(claim.token, commands.now()) && relevant(task)
    }
    fun offerActor(claim: TaskClaim) = OperationsActor(ActorRole.DRIVER, dispatch.offer(claim.subjectId).driverId)
    fun payload(claim: TaskClaim): Map<String, String> = if (claim.kind == TaskKind.PUSH_OFFER) mapOf("eventType" to "DISPATCH_OFFER", "offerId" to "offer_${claim.subjectId}", "requestId" to "req_${claim.requestId}") else mapOf("eventType" to "FOLLOW_UP_REQUIRED", "requestId" to "req_${claim.requestId}")

    @Transactional
    fun searched(claim: TaskClaim, candidates: List<DriverCandidate>?, retryable: Boolean = true) {
        val task = locked(claim) ?: return
        if (!relevant(task)) {
            task.cancel()
            return
        }
        val now = commands.now()
        if (candidates == null) {
            if (retryable && task.attempts < 3) {
                task.retry(now.plusSeconds(if (task.attempts == 1) 5 else 10))
            } else {
                task.finish(false)
                closeSearch(task, RoundStatus.SEARCH_FAILED)
            }
            return
        }
        val context = searchContext(claim)
        val excluded = works.excluded(task.requestId)
        val eligible = candidates.distinctBy { it.driverId }.filter { it.hasValidLocationAt(context.observedAt) && it.driverId !in excluded }
        val distances = spatial.within(context.site, context.radiusKm, eligible)
        // 조회 시점 이후 새 작업을 맡은 기사도 제외한다. 최종 수락에서는 다시 검사한다.
        val occupied = works.occupiedDrivers(distances.keys)
        val available = distances.filterKeys { it !in occupied }
        task.finish(true)
        if (available.isNotEmpty()) {
            dispatch.publish(task.subjectId, available, now, offerLifetime).forEach { offer ->
                tasks.save(OperationsTask(TaskKind.EXPAND_OFFER, task.requestId, offer.offerId, "offer:${offer.offerId}", now))
            }
            return
        }
        if (dispatch.expand(task.subjectId)) {
            val radius = dispatch.round(task.subjectId).radiusKm
            tasks.save(OperationsTask(TaskKind.SEARCH, task.requestId, task.subjectId, "search:${task.subjectId}:$radius", now))
        } else {
            closeSearch(task, RoundStatus.NO_CANDIDATES)
        }
    }

    @Transactional
    fun expanded(claim: TaskClaim, recipients: List<PushRecipient>?) {
        val task = locked(claim) ?: return
        if (!relevant(task)) {
            task.cancel()
            return
        }
        if (recipients == null) {
            retryOrFail(task, null, true)
            aggregateOffer(task)
            return
        }
        val kind = if (task.kind == TaskKind.EXPAND_OFFER) TaskKind.PUSH_OFFER else TaskKind.PUSH_ADMIN
        val subject = if (kind == TaskKind.PUSH_OFFER) task.subjectId else checkNotNull(task.taskId)
        recipients.distinct().forEach { target ->
            require(target.installation.isNotBlank() && target.installation.length <= 200)
            tasks.save(OperationsTask(kind, task.requestId, subject, "delivery:${task.taskId}:${target.actor.role}:${target.actor.id}:${target.installation}", commands.now(), target.actor.role, target.actor.id, target.installation))
        }
        task.finish(true)
        aggregateOffer(task)
    }

    @Transactional
    fun delivered(claim: TaskClaim, success: Boolean, retryable: Boolean = false, retryAfter: Duration? = null) {
        val task = locked(claim) ?: return
        if (success) {
            task.finish(true)
        } else if (!relevant(task)) {
            task.cancel()
        } else {
            retryOrFail(task, retryAfter, retryable)
        }
        aggregateOffer(task)
    }

    @Transactional
    fun expireRequest(requestId: Long) {
        em.clear()
        val request = requests.lock(requestId)
        val roundId = request.currentRoundId ?: return
        if (request.status != RequestStatus.OPEN) return
        dispatch.reconcile(roundId, commands.now())?.let { followUp(requestId, roundId, it) }
    }
    fun expiringRequests() = dispatch.expiringRequests(commands.now(), 100)
    fun expiredClaims() = tasks.expired(commands.now(), 100)

    @Transactional
    fun recover(taskId: Long) {
        val prior = tasks.findById(taskId).orElse(null) ?: return
        val requestId = prior.requestId
        em.clear()
        requests.lock(requestId)
        val task = tasks.lock(taskId) ?: return
        if (task.state != TaskState.RUNNING || checkNotNull(task.leaseUntil) > commands.now()) return
        if (!relevant(task)) task.cancel() else retryOrFail(task, null, true)
        if (task.kind == TaskKind.SEARCH && task.state == TaskState.FAILED) closeSearch(task, RoundStatus.SEARCH_FAILED)
        aggregateOffer(task)
    }

    private fun locked(claim: TaskClaim): OperationsTask? {
        // 외부 호출 전의 조회 객체를 버리고 의뢰 → 작업자 claim 순서로 잠근다.
        em.clear()
        requests.lock(claim.requestId)
        val task = tasks.lock(claim.id) ?: return null
        return task.takeIf { it.held(claim.token, commands.now()) }
    }
    private fun relevant(task: OperationsTask): Boolean {
        val request = requests.get(task.requestId)
        return when (task.kind) {
            TaskKind.SEARCH -> request.status == RequestStatus.OPEN && request.currentRoundId == task.subjectId && dispatch.round(task.subjectId).status == RoundStatus.SEARCHING && task.logicalKey == "search:${task.subjectId}:${dispatch.round(task.subjectId).radiusKm}"
            TaskKind.EXPAND_OFFER, TaskKind.PUSH_OFFER -> {
                val offer = dispatch.offer(task.subjectId)
                request.status == RequestStatus.OPEN && request.currentRoundId == offer.roundId && offer.status == OfferStatus.PENDING && offer.deliveryStatus != DeliveryStatus.FAILED && commands.now() < offer.expiresAt
            }
            TaskKind.EXPAND_ADMIN -> request.status == RequestStatus.FOLLOW_UP_REQUIRED && request.followUpTarget?.let { "followup:${it.kind}:${it.id}" } == task.logicalKey
            TaskKind.PUSH_ADMIN -> tasks.findById(task.subjectId).orElse(null)?.let { relevant(it) } == true
        }
    }
    private fun retryOrFail(task: OperationsTask, retryAfter: Duration?, retryable: Boolean) {
        val admin = task.kind in setOf(TaskKind.EXPAND_ADMIN, TaskKind.PUSH_ADMIN)
        val maximum = if (admin) adminMaxAttempts else 3
        val delay = if (admin) adminRetryDelay else Duration.ofSeconds(if (task.attempts == 1) 5 else 10)
        val due = commands.now().plus(maxOf(delay, retryAfter ?: Duration.ZERO))
        val beforeExpiry = if (task.kind in setOf(TaskKind.EXPAND_OFFER, TaskKind.PUSH_OFFER)) due < dispatch.offer(task.subjectId).expiresAt else true
        if (retryable && task.attempts < maximum && beforeExpiry) task.retry(due) else task.finish(false)
    }
    private fun aggregateOffer(task: OperationsTask) {
        if (task.kind !in setOf(TaskKind.EXPAND_OFFER, TaskKind.PUSH_OFFER)) return
        val offer = dispatch.offer(task.subjectId)
        val request = requests.get(task.requestId)
        if (request.status != RequestStatus.OPEN || request.currentRoundId != offer.roundId || dispatch.round(offer.roundId).status != RoundStatus.OFFERING) return
        if (commands.now() >= offer.expiresAt) {
            dispatch.reconcile(offer.roundId, commands.now())?.let { followUp(task.requestId, offer.roundId, it) }
            return
        }
        tasks.flush()
        val sends = tasks.findAllByKindAndSubjectId(TaskKind.PUSH_OFFER, task.subjectId)
        val expansion = tasks.findAllByKindAndSubjectId(TaskKind.EXPAND_OFFER, task.subjectId)
        val result = when {
            sends.any { it.state == TaskState.SUCCEEDED } -> DeliveryStatus.PROVIDER_ACCEPTED
            (sends + expansion).any { !it.terminal() } -> DeliveryStatus.PENDING
            else -> DeliveryStatus.FAILED
        }
        dispatch.delivery(task.subjectId, result)
        dispatch.reconcile(offer.roundId, commands.now())?.let { followUp(task.requestId, offer.roundId, it) }
    }
    private fun closeSearch(task: OperationsTask, status: RoundStatus) {
        dispatch.close(task.subjectId, status, commands.now())
        followUp(task.requestId, task.subjectId, status)
    }
    private fun followUp(requestId: Long, roundId: Long, status: RoundStatus) {
        val now = commands.now()
        requests.followUp(requestId, FollowUpTarget("ROUND", roundId), status.name, now)
        tasks.save(OperationsTask(TaskKind.EXPAND_ADMIN, requestId, roundId, "followup:ROUND:$roundId", now))
    }
}
