package com.allcheck.operations.coordination.application

import com.allcheck.global.exception.BusinessException
import com.allcheck.operations.ActorRole
import com.allcheck.operations.CancellationReason
import com.allcheck.operations.CandidateContext
import com.allcheck.operations.FollowUpChoice
import com.allcheck.operations.FollowUpTarget
import com.allcheck.operations.OperationsActor
import com.allcheck.operations.OperationsDrivers
import com.allcheck.operations.RequestIntake
import com.allcheck.operations.RequestStatus
import com.allcheck.operations.SiteCoordinates
import com.allcheck.operations.WorkStatus
import com.allcheck.operations.WorkType
import com.allcheck.operations.dispatch.DispatchOperations
import com.allcheck.operations.domain.entity.OperationsTask
import com.allcheck.operations.domain.entity.TaskKind
import com.allcheck.operations.exception.OperationsErrorCode
import com.allcheck.operations.infrastructure.geo.OperationsSpatial
import com.allcheck.operations.infrastructure.repository.OperationsTaskRepository
import com.allcheck.operations.request.RequestOperations
import com.allcheck.operations.validInput
import com.allcheck.operations.work.WorkOperations
import com.allcheck.reception.ReceptionOperations
import com.allcheck.reception.ReceptionSnapshot
import jakarta.persistence.EntityManager
import org.springframework.beans.factory.ObjectProvider
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
@Transactional(readOnly = true)
class OperationsTransactions(
    private val commands: OperationsCommands,
    private val receptions: ReceptionOperations,
    private val requests: RequestOperations,
    private val dispatch: DispatchOperations,
    private val works: WorkOperations,
    private val tasks: OperationsTaskRepository,
    private val spatial: OperationsSpatial,
    private val em: EntityManager,
    private val drivers: ObjectProvider<OperationsDrivers>,
) {
    @Transactional
    fun register(actor: OperationsActor, receptionId: Long, expected: Int, key: String, source: ReceptionSnapshot, site: SiteCoordinates): CommandResult {
        role(actor, ActorRole.ADMIN)
        return commands.execute(actor, "REGISTER", receptionId, key, expected) {
            commands.receptionLock(receptionId)
            // 좌표 조회 이후 다시 확인한다. reception의 낙관적 잠금이 확정 취소와의 경합을 보호한다.
            val current = receptions.confirmedSnapshot(actor.id, receptionId, expected)
            validInput(source == current)
            val now = commands.now()
            val intake = RequestIntake(WorkType.valueOf(current.workType), current.symptoms, current.address, current.desiredVisitFrom, current.desiredVisitTo, current.contactPhone, current.notes)
            val request = requests.create(receptionId, actor.id, intake, site, now)
            receptions.registerRequest(actor.id, receptionId, expected, request.requestId)
            val round = dispatch.create(request.requestId, now)
            requests.startRound(request.requestId, round.roundId, now)
            tasks.save(OperationsTask(TaskKind.SEARCH, request.requestId, round.roundId, "search:${round.roundId}:10", now))
            val saved = requests.get(request.requestId)
            CommandOutcome(saved.requestId, saved.requestId, saved.status.name, saved.version, round.roundId, now)
        }
    }

    @Transactional
    fun respond(actor: OperationsActor, offerId: Long, key: String, accept: Boolean): CommandResult {
        role(actor, ActorRole.DRIVER)
        val offered = dispatch.offer(offerId)
        own(actor.id == offered.driverId)
        return commands.execute(actor, "RESPOND", offerId, key, accept) {
            val request = lockRequest(offered.requestId)
            val offer = dispatch.offer(offerId)
            state(request.status == RequestStatus.OPEN && request.currentRoundId == offer.roundId)
            val now = commands.now()
            if (!accept) {
                dispatch.reject(offerId, now)
                dispatch.reconcile(offer.roundId, now)?.let { followUp(request.requestId, FollowUpTarget("ROUND", offer.roundId), it.name) }
                return@execute CommandOutcome(offerId, request.requestId, "REJECTED", request.version, at = now)
            }
            commands.driverLock(actor.id)
            val context = CandidateContext(request.intake.workType, request.site, dispatch.round(offer.roundId).radiusKm, now)
            val bridge = drivers.ifAvailable ?: throw BusinessException(OperationsErrorCode.OPERATIONS_INTEGRATION_UNAVAILABLE)
            val availability = bridge.lockForAcceptance(actor.id, context)
            if (availability == null ||
                availability.driverId != actor.id ||
                !availability.eligible(context.copy(observedAt = commands.now())) ||
                works.occupied(actor.id) ||
                actor.id in works.excluded(request.requestId) ||
                spatial.within(request.site, context.radiusKm, listOf(availability)).isEmpty()
            ) {
                throw BusinessException(OperationsErrorCode.DRIVER_NOT_ELIGIBLE)
            }
            // 자격 행 잠금과 공간 조회 대기 중 만료되었을 수 있으므로 수락 직전에 다시 읽는다.
            val acceptedAt = commands.now()
            if (!availability.eligible(context.copy(observedAt = acceptedAt))) throw BusinessException(OperationsErrorCode.DRIVER_NOT_ELIGIBLE)
            dispatch.accept(offerId, acceptedAt)
            val work = works.create(request.requestId, offer.roundId, offerId, actor.id, request.currentWorkId, acceptedAt)
            requests.accept(request.requestId, offer.roundId, work.workId, acceptedAt)
            CommandOutcome(work.workId, request.requestId, work.status.name, work.version, offerId, acceptedAt)
        }
    }

    @Transactional
    fun transition(actor: OperationsActor, workId: Long, key: String, expected: Int, target: WorkStatus, reason: String?, planned: java.time.Instant?): CommandResult {
        role(actor, ActorRole.DRIVER)
        val prior = works.get(workId)
        own(prior.driverId == actor.id)
        return commands.execute(actor, "TRANSITION", workId, key, listOf(expected, target, reason, planned)) {
            currentWork(prior.requestId, workId)
            val now = commands.now()
            val work = works.transition(workId, actor.id, expected, target, reason, planned, now)
            CommandOutcome(workId, work.requestId, work.status.name, work.version, at = now)
        }
    }

    @Transactional
    fun revisit(actor: OperationsActor, workId: Long, key: String, expected: Int, planned: java.time.Instant?): CommandResult {
        role(actor, ActorRole.DRIVER)
        val prior = works.get(workId)
        own(prior.driverId == actor.id)
        return commands.execute(actor, "REVISIT", workId, key, listOf(expected, planned)) {
            currentWork(prior.requestId, workId)
            val now = commands.now()
            val work = works.planRevisit(workId, actor.id, expected, planned, now)
            CommandOutcome(workId, work.requestId, work.status.name, work.version, at = now)
        }
    }

    @Transactional
    fun cancel(actor: OperationsActor, workId: Long, key: String, expected: Int, code: CancellationReason, detail: String): CommandResult {
        role(actor, ActorRole.DRIVER)
        val prior = works.get(workId)
        own(prior.driverId == actor.id)
        return commands.execute(actor, "CANCEL", workId, key, listOf(expected, code, detail)) {
            currentWork(prior.requestId, workId)
            val now = commands.now()
            val work = works.cancel(workId, actor.id, expected, code, detail, now)
            val cancellation = checkNotNull(work.cancellation)
            followUp(work.requestId, FollowUpTarget("CANCELLATION", cancellation.id), "WORK_CANCELLED")
            CommandOutcome(cancellation.id, work.requestId, work.status.name, work.version, workId, now)
        }
    }

    @Transactional
    fun decide(actor: OperationsActor, requestId: Long, key: String, expected: Int, target: FollowUpTarget, choice: FollowUpChoice): CommandResult {
        role(actor, ActorRole.ADMIN)
        return commands.execute(actor, "DECIDE", requestId, key, listOf(expected, target, choice)) {
            val previous = lockRequest(requestId)
            com.allcheck.operations.expectedVersion(previous.version, expected)
            state(previous.status == RequestStatus.FOLLOW_UP_REQUIRED && previous.followUpTarget == target)
            val now = commands.now()
            // 구 회차가 이미 종료되어 있으므로 새 회차 삽입은 단일 활성 회차 제약과 충돌하지 않는다.
            val round = if (choice == FollowUpChoice.REDISPATCH) dispatch.create(requestId, now) else null
            val decision = requests.decide(requestId, expected, target, choice, actor.id, round?.roundId, now)
            if (round != null) {
                requests.startRound(requestId, round.roundId, now)
                tasks.save(OperationsTask(TaskKind.SEARCH, requestId, round.roundId, "search:${round.roundId}:10", now))
            }
            val request = requests.get(requestId)
            CommandOutcome(decision.decisionId, requestId, request.status.name, request.version, round?.roundId, now)
        }
    }

    @Transactional
    fun signal(actor: OperationsActor, offerId: Long, viewed: Boolean) {
        role(actor, ActorRole.DRIVER)
        val offer = dispatch.offer(offerId)
        own(offer.driverId == actor.id)
        lockRequest(offer.requestId)
        dispatch.signal(offerId, viewed, commands.now())
    }
    private fun currentWork(requestId: Long, workId: Long) {
        val request = lockRequest(requestId)
        state(request.status == RequestStatus.ACCEPTED && request.currentWorkId == workId)
    }
    private fun followUp(requestId: Long, target: FollowUpTarget, reason: String) {
        val now = commands.now()
        requests.followUp(requestId, target, reason, now)
        tasks.save(OperationsTask(TaskKind.EXPAND_ADMIN, requestId, target.id, "followup:${target.kind}:${target.id}", now))
    }
    private fun lockRequest(requestId: Long): com.allcheck.operations.request.RequestSnapshot {
        // 잠금 대기 전에 읽었던 offer/work가 1차 캐시에 남으면 잠금 획득 후에도 구 상태가 보인다.
        // 이 지점은 각 명령의 첫 쓰기 이전이며, 이후 모든 업무 엔티티를 잠금 아래 다시 읽는다.
        em.clear()
        return requests.lock(requestId)
    }
    private fun role(actor: OperationsActor, required: ActorRole) = own(actor.role == required)
    private fun own(value: Boolean) {
        if (!value) throw BusinessException(OperationsErrorCode.OPERATIONS_NOT_FOUND)
    }
    private fun state(value: Boolean) {
        if (!value) throw BusinessException(OperationsErrorCode.OPERATIONS_STATE_CONFLICT)
    }
}
