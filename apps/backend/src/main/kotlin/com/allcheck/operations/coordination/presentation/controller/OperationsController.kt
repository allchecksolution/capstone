package com.allcheck.operations.coordination.presentation.controller

import com.allcheck.operations.ReportContent
import com.allcheck.operations.RequestStatus
import com.allcheck.operations.coordination.application.CommandResult
import com.allcheck.operations.coordination.application.OperationsPhotos
import com.allcheck.operations.coordination.application.OperationsQueries
import com.allcheck.operations.coordination.application.OperationsReportTransactions
import com.allcheck.operations.coordination.application.OperationsService
import com.allcheck.operations.coordination.application.OperationsTransactions
import com.allcheck.operations.coordination.presentation.api.OperationsApi
import com.allcheck.operations.coordination.presentation.dto.CancelWorkRequest
import com.allcheck.operations.coordination.presentation.dto.FollowUpDecisionRequest
import com.allcheck.operations.coordination.presentation.dto.OperationResponse
import com.allcheck.operations.coordination.presentation.dto.OperationsId
import com.allcheck.operations.coordination.presentation.dto.RegisterServiceRequest
import com.allcheck.operations.coordination.presentation.dto.RespondOfferRequest
import com.allcheck.operations.coordination.presentation.dto.ReviseReportRequest
import com.allcheck.operations.coordination.presentation.dto.RevisitPlanRequest
import com.allcheck.operations.coordination.presentation.dto.SubmitReportRequest
import com.allcheck.operations.coordination.presentation.dto.TransitionWorkRequest
import com.allcheck.operations.coordination.presentation.dto.WorkPhotoUploadRequest
import com.allcheck.operations.coordination.presentation.security.OperationsActorResolver
import org.springframework.http.CacheControl
import org.springframework.http.ResponseEntity
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.security.core.Authentication
import org.springframework.web.bind.annotation.RestController
import java.net.URI

@RestController
@PreAuthorize("hasAnyRole('ADMIN','DRIVER')")
class OperationsController(
    private val actors: OperationsActorResolver,
    private val service: OperationsService,
    private val transactions: OperationsTransactions,
    private val reports: OperationsReportTransactions,
    private val queries: OperationsQueries,
    private val photos: OperationsPhotos,
) : OperationsApi {
    @PreAuthorize("hasRole('ADMIN')")
    override fun reports(authentication: Authentication, page: Int, size: Int) = ok(queries.reports(actors.resolve(authentication), page, size))
    override fun cancellations(authentication: Authentication, page: Int, size: Int) = ok(queries.cancellations(actors.resolve(authentication), page, size))
    override fun cancellation(authentication: Authentication, cancellationId: String) = ok(queries.cancellation(actors.resolve(authentication), OperationsId.CANCELLATION.parse(cancellationId)))
    override fun register(authentication: Authentication, key: String, body: RegisterServiceRequest) = command(service.register(actors.resolve(authentication), OperationsId.RECEPTION.parse(body.receptionId), body.expectedVersion, key), OperationsId.REQUEST, OperationsId.ROUND, "/api/v1/service-requests")
    override fun requests(authentication: Authentication, status: RequestStatus?, page: Int, size: Int) = ok(queries.requests(actors.resolve(authentication), status, page, size))
    override fun request(authentication: Authentication, requestId: String) = ok(queries.request(actors.resolve(authentication), OperationsId.REQUEST.parse(requestId)))
    override fun history(authentication: Authentication, requestId: String) = ok(queries.history(actors.resolve(authentication), OperationsId.REQUEST.parse(requestId)))
    override fun rounds(authentication: Authentication, requestId: String) = ok(queries.history(actors.resolve(authentication), OperationsId.REQUEST.parse(requestId)).rounds)
    override fun decide(authentication: Authentication, requestId: String, key: String, body: FollowUpDecisionRequest) = command(transactions.decide(actors.resolve(authentication), OperationsId.REQUEST.parse(requestId), key, body.expectedVersion, body.target.target(), body.decision), OperationsId.DECISION, OperationsId.ROUND)
    override fun offers(authentication: Authentication, page: Int, size: Int) = ok(queries.offers(actors.resolve(authentication), page, size))
    override fun offer(authentication: Authentication, offerId: String) = ok(queries.offer(actors.resolve(authentication), OperationsId.OFFER.parse(offerId)))
    override fun respond(authentication: Authentication, offerId: String, key: String, body: RespondOfferRequest) = command(service.respond(actors.resolve(authentication), OperationsId.OFFER.parse(offerId), key, body.response == "ACCEPT"), if (body.response == "ACCEPT") OperationsId.WORK else OperationsId.OFFER, OperationsId.OFFER)
    override fun received(authentication: Authentication, offerId: String): ResponseEntity<Void> {
        transactions.signal(actors.resolve(authentication), OperationsId.OFFER.parse(offerId), false)
        return ResponseEntity.noContent().build()
    }
    override fun viewed(authentication: Authentication, offerId: String): ResponseEntity<Void> {
        transactions.signal(actors.resolve(authentication), OperationsId.OFFER.parse(offerId), true)
        return ResponseEntity.noContent().build()
    }
    override fun works(authentication: Authentication, scope: String, month: String?, page: Int, size: Int) = ok(queries.driverWorks(actors.resolve(authentication), scope, month, page, size))
    override fun statistics(authentication: Authentication, month: String) = ok(queries.statistics(actors.resolve(authentication), month))
    override fun work(authentication: Authentication, workId: String) = ok(queries.work(actors.resolve(authentication), OperationsId.WORK.parse(workId)))

    @PreAuthorize("hasRole('DRIVER')")
    override fun transition(authentication: Authentication, workId: String, key: String, body: TransitionWorkRequest) = command(transactions.transition(actors.resolve(authentication), OperationsId.WORK.parse(workId), key, body.expectedVersion, body.targetStatus, body.reason, body.plannedRevisitAt), OperationsId.WORK)

    @PreAuthorize("hasRole('DRIVER')")
    override fun revisit(authentication: Authentication, workId: String, key: String, body: RevisitPlanRequest) = command(transactions.revisit(actors.resolve(authentication), OperationsId.WORK.parse(workId), key, body.expectedVersion, body.plannedRevisitAt), OperationsId.WORK)

    @PreAuthorize("hasRole('DRIVER')")
    override fun cancel(authentication: Authentication, workId: String, key: String, body: CancelWorkRequest) = command(transactions.cancel(actors.resolve(authentication), OperationsId.WORK.parse(workId), key, body.expectedVersion, body.reasonCode, body.reasonDetail), OperationsId.CANCELLATION, OperationsId.WORK, "/api/v1/dispatch-cancellations")

    @PreAuthorize("hasRole('DRIVER')")
    override fun submit(authentication: Authentication, workId: String, key: String, body: SubmitReportRequest) = command(reports.submit(actors.resolve(authentication), OperationsId.WORK.parse(workId), key, body.expectedVersion, ReportContent(body.workDescription, body.diagnosis, body.totalCost), body.beforePhotoIds.map(OperationsId.PHOTO::parse), body.afterPhotoIds.map(OperationsId.PHOTO::parse)), OperationsId.REPORT, OperationsId.WORK, "/api/v1/reports")
    override fun report(authentication: Authentication, reportId: String) = ok(queries.report(actors.resolve(authentication), OperationsId.REPORT.parse(reportId)))

    @PreAuthorize("hasRole('ADMIN')")
    override fun revise(authentication: Authentication, reportId: String, key: String, body: ReviseReportRequest) = command(reports.revise(actors.resolve(authentication), OperationsId.REPORT.parse(reportId), key, body.expectedVersion, body.reason, body.changes.workDescription, body.changes.diagnosis, body.changes.totalCost), OperationsId.REPORT, OperationsId.WORK, "/api/v1/reports")

    @PreAuthorize("hasRole('DRIVER')")
    override fun reservePhoto(authentication: Authentication, workId: String, key: String, body: WorkPhotoUploadRequest): ResponseEntity<com.allcheck.operations.coordination.presentation.dto.PhotoUploadResponse> {
        val (grant, replay) = photos.reserve(actors.resolve(authentication), OperationsId.WORK.parse(workId), key, body.metadata())
        return ResponseEntity.status(201).cacheControl(CacheControl.noStore()).header("Idempotency-Replayed", replay.toString()).body(grant)
    }

    @PreAuthorize("hasRole('DRIVER')")
    override fun renewPhoto(authentication: Authentication, workId: String, photoId: String) = ResponseEntity.status(201).cacheControl(CacheControl.noStore()).body(photos.grant(actors.resolve(authentication), OperationsId.WORK.parse(workId), OperationsId.PHOTO.parse(photoId)))

    @PreAuthorize("hasRole('DRIVER')")
    override fun completePhoto(authentication: Authentication, workId: String, photoId: String, key: String) = command(photos.complete(actors.resolve(authentication), OperationsId.WORK.parse(workId), OperationsId.PHOTO.parse(photoId), key), OperationsId.PHOTO, OperationsId.WORK)
    override fun photoAccess(authentication: Authentication, workId: String, photoId: String) = ok(photos.access(actors.resolve(authentication), OperationsId.WORK.parse(workId), OperationsId.PHOTO.parse(photoId)))
    private fun <T : Any> ok(value: T) = ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(value)
    private fun command(result: CommandResult, resource: OperationsId, related: OperationsId? = null, createdPath: String? = null): ResponseEntity<OperationResponse> {
        val outcome = result.value
        val response = OperationResponse(resource.format(outcome.resourceId), OperationsId.REQUEST.format(outcome.requestId), outcome.status, outcome.version, outcome.at, outcome.linkedId?.let { related?.format(it) })
        val builder = if (createdPath != null) ResponseEntity.created(URI.create("$createdPath/${response.resourceId}")) else ResponseEntity.ok()
        return builder.cacheControl(CacheControl.noStore()).header("Idempotency-Replayed", result.replayed.toString()).body(response)
    }
}
