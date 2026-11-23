package com.allcheck.operations.coordination.application

import com.allcheck.global.exception.BusinessException
import com.allcheck.global.exception.GlobalErrorCode
import com.allcheck.operations.ActorRole
import com.allcheck.operations.OfferStatus
import com.allcheck.operations.OperationsActor
import com.allcheck.operations.RequestStatus
import com.allcheck.operations.WorkStatus
import com.allcheck.operations.WorkType
import com.allcheck.operations.coordination.presentation.dto.response.CancellationResponse
import com.allcheck.operations.coordination.presentation.dto.response.DecisionResponse
import com.allcheck.operations.coordination.presentation.dto.response.DispatchHistoryResponse
import com.allcheck.operations.coordination.presentation.dto.response.DispatchOfferResponse
import com.allcheck.operations.coordination.presentation.dto.response.FollowUpTargetResponse
import com.allcheck.operations.coordination.presentation.dto.response.MonthlyStatisticsResponse
import com.allcheck.operations.coordination.presentation.dto.response.OfferHistoryResponse
import com.allcheck.operations.coordination.presentation.dto.response.OperationsPage
import com.allcheck.operations.coordination.presentation.dto.response.ReportRevisionResponse
import com.allcheck.operations.coordination.presentation.dto.response.RoundHistoryResponse
import com.allcheck.operations.coordination.presentation.dto.response.ServiceRequestResponse
import com.allcheck.operations.coordination.presentation.dto.response.WorkEventResponse
import com.allcheck.operations.coordination.presentation.dto.response.WorkReportResponse
import com.allcheck.operations.coordination.presentation.dto.response.WorkResponse
import com.allcheck.operations.coordination.presentation.dto.response.WorkTypeDistribution
import com.allcheck.operations.dispatch.DispatchOperations
import com.allcheck.operations.dispatch.OfferSnapshot
import com.allcheck.operations.exception.OperationsErrorCode
import com.allcheck.operations.request.RequestOperations
import com.allcheck.operations.request.RequestSnapshot
import com.allcheck.operations.validInput
import com.allcheck.operations.work.ReportOperations
import com.allcheck.operations.work.WorkOperations
import com.allcheck.operations.work.WorkSnapshot
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.YearMonth
import java.time.ZoneId

@Service
@Transactional(readOnly = true)
class OperationsQueries(private val requests: RequestOperations, private val dispatch: DispatchOperations, private val works: WorkOperations, private val reports: ReportOperations, private val commands: OperationsCommands) {
    fun request(actor: OperationsActor, requestId: Long): ServiceRequestResponse {
        own(actor.role == ActorRole.ADMIN)
        return requests.get(requestId).response()
    }
    fun offers(actor: OperationsActor, page: Int, size: Int): OperationsPage<DispatchOfferResponse> {
        own(actor.role == ActorRole.DRIVER)
        bounds(page, size)
        val rows = dispatch.driverOffers(actor.id, page, size)
        return OperationsPage(rows.map { offerResponse(it) }, page, size, dispatch.driverOffers(actor.id, page + 1, size).isNotEmpty())
    }
    fun offer(actor: OperationsActor, offerId: Long): DispatchOfferResponse {
        val offer = dispatch.offer(offerId)
        own(actor.role == ActorRole.DRIVER && offer.driverId == actor.id)
        return offerResponse(offer)
    }
    fun work(actor: OperationsActor, workId: Long): WorkResponse {
        val work = works.get(workId)
        authorizeWork(actor, work)
        return workResponse(actor, work, reports.forWorks(listOf(workId))[workId])
    }
    fun driverWorks(actor: OperationsActor, scope: String, month: String?, page: Int, size: Int): OperationsPage<WorkResponse> {
        own(actor.role == ActorRole.DRIVER)
        bounds(page, size)
        validInput(scope in setOf("CURRENT", "HISTORY") && (scope == "HISTORY" || month == null))
        val range = month?.let { monthRange(it) }
        val list = works.driverWorks(actor.id, scope == "HISTORY", range?.first, range?.second, page, size)
        val reportIds = reports.forWorks(list.map { it.workId })
        val more = works.driverWorks(actor.id, scope == "HISTORY", range?.first, range?.second, page + 1, size).isNotEmpty()
        return OperationsPage(list.map { workResponse(actor, it, reportIds[it.workId]) }, page, size, more)
    }
    fun history(actor: OperationsActor, requestId: Long): DispatchHistoryResponse {
        own(actor.role == ActorRole.ADMIN)
        val workList = works.forRequest(requestId)
        val workByRound = workList.associateBy { it.roundId }
        val reportIds = reports.forWorks(workList.map { it.workId })
        val rounds = dispatch.rounds(requestId).map { round ->
            RoundHistoryResponse(
                id("round", round.roundId),
                round.status,
                round.radiusKm,
                round.createdAt,
                round.closedAt,
                dispatch.offers(round.roundId).map { OfferHistoryResponse(id("offer", it.offerId), id("drv", it.driverId), it.status, it.deliveryStatus.name, it.respondedAt, it.expiresAt, it.createdAt, it.receivedAt, it.viewedAt) },
                workByRound[round.roundId]?.let { workResponse(actor, it, reportIds[it.workId]) },
            )
        }
        val decisions = requests.decisions(requestId).map { DecisionResponse(id("decision", it.decisionId), id("adm", it.adminId), target(it.target), it.decision, it.newRoundId?.let { round -> id("round", round) }, it.decidedAt) }
        return DispatchHistoryResponse(request(actor, requestId), rounds, decisions)
    }
    fun report(actor: OperationsActor, reportId: Long): WorkReportResponse {
        val report = reports.get(reportId)
        authorizeWork(actor, works.get(report.workId))
        return WorkReportResponse(
            id("report", reportId), id("work", report.workId), report.original, report.latest,
            report.beforePhotoIds.map { id("photo", it) }, report.afterPhotoIds.map { id("photo", it) }, report.version, report.submittedAt,
            report.revisions.map { ReportRevisionResponse(id("revision", it.revisionId), it.version, id("adm", it.adminId), it.reason, it.before, it.after, it.revisedAt) },
        )
    }
    fun photo(actor: OperationsActor, workId: Long, photoId: Long): com.allcheck.operations.work.PhotoSnapshot {
        authorizeWork(actor, works.get(workId))
        val photo = reports.photo(photoId)
        own(photo.workId == workId)
        return photo
    }
    fun statistics(actor: OperationsActor, month: String): MonthlyStatisticsResponse {
        own(actor.role == ActorRole.DRIVER)
        val (from, to) = monthRange(month)
        val facts = works.statistics(actor.id, from, to)
        return MonthlyStatisticsResponse(
            month,
            facts.assignments,
            facts.completed,
            facts.cancelled,
            WorkType.entries.map { type ->
                val count = facts.completedWorkTypes[type.name] ?: 0
                WorkTypeDistribution(type, count, if (facts.completed == 0L) 0.0 else count.toDouble() / facts.completed)
            },
        )
    }
    fun cancellations(actor: OperationsActor, page: Int, size: Int): OperationsPage<WorkResponse> {
        own(actor.role == ActorRole.ADMIN)
        bounds(page, size)
        val list = works.cancelled(page, size)
        val ids = reports.forWorks(list.map { it.workId })
        return OperationsPage(list.map { workResponse(actor, it, ids[it.workId]) }, page, size, works.cancelled(page + 1, size).isNotEmpty())
    }
    fun cancellation(actor: OperationsActor, cancellationId: Long): DispatchHistoryResponse {
        own(actor.role == ActorRole.ADMIN)
        return history(actor, works.cancellation(cancellationId).requestId)
    }
    fun reports(actor: OperationsActor, page: Int, size: Int): OperationsPage<WorkReportResponse> {
        own(actor.role == ActorRole.ADMIN)
        bounds(page, size)
        val rows = reports.list(page, size)
        return OperationsPage(rows.map { report(actor, it.reportId) }, page, size, reports.list(page + 1, size).isNotEmpty())
    }
    private fun authorizeWork(actor: OperationsActor, work: WorkSnapshot) {
        if (actor.role == ActorRole.ADMIN || work.driverId == actor.id) return
        val request = requests.get(work.requestId)
        val chain = works.forRequest(work.requestId).associateBy { it.workId }
        var current = request.currentWorkId?.let { chain[it] }
        own(current != null && current.driverId == actor.id && current.unfinished() && request.status == RequestStatus.ACCEPTED)
        val visited = mutableSetOf<Long>()
        while (current != null && visited.add(current.workId)) {
            if (current.workId == work.workId) return
            current = current.previousWorkId?.let { chain[it] }
        }
        own(false)
    }
    private fun workResponse(actor: OperationsActor, work: WorkSnapshot, reportId: Long?): WorkResponse {
        val request = requests.get(work.requestId)
        val current = work.driverId == actor.id && request.currentWorkId == work.workId && request.status == RequestStatus.ACCEPTED && work.unfinished()
        val intake = request.intake
        return WorkResponse(
            id("work", work.workId), id("req", work.requestId), id("drv", work.driverId), work.previousWorkId?.let { id("work", it) }, work.status, work.version, intake.workType, intake.symptoms, intake.address,
            intake.contactPhone.takeIf { actor.role == ActorRole.ADMIN || current }, intake.desiredVisitFrom, intake.desiredVisitTo, intake.notes, work.assignedAt, work.completedAt, work.cancelledAt, reportId?.let { id("report", it) },
            work.cancellation?.let { CancellationResponse(id("cancel", it.id), id("work", work.workId), id("req", work.requestId), it.reasonCode, it.reasonDetail, it.previousStatus, it.cancelledAt) },
            work.events.map { WorkEventResponse(it.status, it.at, it.reason, it.plannedRevisitAt) }, actor.role == ActorRole.DRIVER && current, actor.role == ActorRole.DRIVER && current && work.status == WorkStatus.IN_PROGRESS && reportId == null,
        )
    }
    private fun offerResponse(offer: OfferSnapshot): DispatchOfferResponse {
        val request = requests.get(offer.requestId)
        return DispatchOfferResponse(
            id("offer", offer.offerId), id("req", offer.requestId), id("round", offer.roundId), offer.status, offer.deliveryStatus.name, request.intake.workType, request.intake.symptoms, request.site.area, offer.distanceMeters, request.intake.desiredVisitFrom, request.intake.desiredVisitTo, request.intake.notes, offer.createdAt, offer.expiresAt,
            offer.status == OfferStatus.PENDING && offer.deliveryStatus != com.allcheck.operations.DeliveryStatus.FAILED && commands.now() < offer.expiresAt && request.status == RequestStatus.OPEN && request.currentRoundId == offer.roundId, offer.receivedAt, offer.viewedAt,
        )
    }
    private fun RequestSnapshot.response() = ServiceRequestResponse(id("req", requestId), id("rec", receptionId), status, version, intake.workType, intake.symptoms, intake.address, intake.desiredVisitFrom, intake.desiredVisitTo, intake.contactPhone, intake.notes, currentRoundId?.let { id("round", it) }, currentWorkId?.let { id("work", it) }, followUpTarget?.let { target(it) }, followUpReason, createdAt)
    private fun target(target: com.allcheck.operations.FollowUpTarget) = FollowUpTargetResponse(target.id.takeIf { target.kind == "CANCELLATION" }?.let { id("cancel", it) }, target.id.takeIf { target.kind == "ROUND" }?.let { id("round", it) })
    private fun monthRange(value: String): Pair<java.time.Instant, java.time.Instant> {
        validInput(value.matches(Regex("[0-9]{4}-(0[1-9]|1[0-2])")))
        val month = try {
            YearMonth.parse(value)
        } catch (_: Exception) {
            throw BusinessException(GlobalErrorCode.INVALID_INPUT_VALUE)
        }
        val zone = ZoneId.of("Asia/Seoul")
        return month.atDay(1).atStartOfDay(zone).toInstant() to month.plusMonths(1).atDay(1).atStartOfDay(zone).toInstant()
    }
    private fun bounds(page: Int, size: Int) = validInput(page in 1..1_000_000 && size in 1..100)
    private fun own(value: Boolean) {
        if (!value) throw BusinessException(OperationsErrorCode.OPERATIONS_NOT_FOUND)
    }
    private fun id(prefix: String, number: Long) = "${prefix}_$number"
}
