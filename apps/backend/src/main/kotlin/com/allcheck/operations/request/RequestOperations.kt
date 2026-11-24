package com.allcheck.operations.request

import com.allcheck.operations.FollowUpChoice
import com.allcheck.operations.FollowUpTarget
import com.allcheck.operations.RequestIntake
import com.allcheck.operations.RequestStatus
import com.allcheck.operations.SiteCoordinates
import java.time.Instant

interface RequestOperations {
    fun create(receptionId: Long, adminId: Long, intake: RequestIntake, site: SiteCoordinates, now: Instant): RequestSnapshot

    /** 명령 트랜잭션은 항상 의뢰 → 기사 순서로 잠근다. 외부 호출 중에는 잠그지 않는다. */
    fun lock(requestId: Long): RequestSnapshot
    fun get(requestId: Long): RequestSnapshot
    fun startRound(requestId: Long, roundId: Long, now: Instant)
    fun accept(requestId: Long, roundId: Long, workId: Long, now: Instant)
    fun followUp(requestId: Long, target: FollowUpTarget, reason: String, now: Instant)
    fun decide(requestId: Long, version: Int, target: FollowUpTarget, choice: FollowUpChoice, adminId: Long, newRoundId: Long?, now: Instant): DecisionSnapshot
    fun complete(requestId: Long, workId: Long, now: Instant)
    fun decisions(requestId: Long): List<DecisionSnapshot>
}

data class RequestSnapshot(
    val requestId: Long,
    val receptionId: Long,
    val createdByAdminId: Long,
    val intake: RequestIntake,
    val site: SiteCoordinates,
    val status: RequestStatus,
    val version: Int,
    val currentRoundId: Long?,
    val currentWorkId: Long?,
    val followUpTarget: FollowUpTarget?,
    val followUpReason: String?,
    val createdAt: Instant,
    val updatedAt: Instant,
)
data class DecisionSnapshot(val decisionId: Long, val requestId: Long, val target: FollowUpTarget, val decision: FollowUpChoice, val adminId: Long, val newRoundId: Long?, val decidedAt: Instant)
