package com.allcheck.operations.request.application

import com.allcheck.global.exception.BusinessException
import com.allcheck.operations.FollowUpChoice
import com.allcheck.operations.FollowUpTarget
import com.allcheck.operations.RequestIntake
import com.allcheck.operations.SiteCoordinates
import com.allcheck.operations.exception.OperationsErrorCode
import com.allcheck.operations.request.DecisionSnapshot
import com.allcheck.operations.request.RequestOperations
import com.allcheck.operations.request.RequestSnapshot
import com.allcheck.operations.request.domain.entity.FollowUpDecision
import com.allcheck.operations.request.domain.entity.ServiceRequest
import com.allcheck.operations.request.infrastructure.repository.FollowUpDecisionRepository
import com.allcheck.operations.request.infrastructure.repository.ServiceRequestRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

@Service
@Transactional(readOnly = true)
class RequestService(private val requests: ServiceRequestRepository, private val decisions: FollowUpDecisionRepository) : RequestOperations {
    @Transactional(propagation = Propagation.MANDATORY)
    override fun create(receptionId: Long, adminId: Long, intake: RequestIntake, site: SiteCoordinates, now: Instant) = requests.saveAndFlush(ServiceRequest(receptionId, adminId, intake, site, now)).snapshot()

    @Transactional(propagation = Propagation.MANDATORY)
    override fun lock(requestId: Long) = (requests.lock(requestId) ?: missing()).snapshot()
    override fun get(requestId: Long) = entity(requestId).snapshot()

    @Transactional(propagation = Propagation.MANDATORY)
    override fun startRound(requestId: Long, roundId: Long, now: Instant) = entity(requestId).startRound(roundId, now)

    @Transactional(propagation = Propagation.MANDATORY)
    override fun accept(requestId: Long, roundId: Long, workId: Long, now: Instant) = entity(requestId).accept(roundId, workId, now)

    @Transactional(propagation = Propagation.MANDATORY)
    override fun followUp(requestId: Long, target: FollowUpTarget, reason: String, now: Instant) = entity(requestId).followUp(target, reason, now)

    @Transactional(propagation = Propagation.MANDATORY)
    override fun complete(requestId: Long, workId: Long, now: Instant) = entity(requestId).complete(workId, now)

    @Transactional(propagation = Propagation.MANDATORY)
    override fun decide(requestId: Long, version: Int, target: FollowUpTarget, choice: FollowUpChoice, adminId: Long, newRoundId: Long?, now: Instant): DecisionSnapshot {
        entity(requestId).decide(version, target, choice, now)
        return decisions.saveAndFlush(FollowUpDecision(requestId, target, choice, adminId, newRoundId, now)).snapshot()
    }
    override fun decisions(requestId: Long) = decisions.findAllByRequestIdOrderByDecisionId(requestId).map { it.snapshot() }
    private fun entity(id: Long) = requests.findById(id).orElseThrow { BusinessException(OperationsErrorCode.OPERATIONS_NOT_FOUND) }
    private fun missing(): Nothing = throw BusinessException(OperationsErrorCode.OPERATIONS_NOT_FOUND)
    private fun ServiceRequest.snapshot() = RequestSnapshot(checkNotNull(requestId), receptionId, createdByAdminId, intake, site, status, version, currentRoundId, currentWorkId, followUpTarget, followUpReason, createdAt, updatedAt)
    private fun FollowUpDecision.snapshot() = DecisionSnapshot(checkNotNull(decisionId), requestId, target, choice, adminId, newRoundId, decidedAt)
}
