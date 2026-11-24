package com.allcheck.operations.coordination.presentation.controller

import com.allcheck.global.security.principal.AuthPrincipal
import com.allcheck.operations.ReportContent
import com.allcheck.operations.coordination.application.CommandResult
import com.allcheck.operations.coordination.application.OperationsPhotos
import com.allcheck.operations.coordination.application.OperationsQueries
import com.allcheck.operations.coordination.application.OperationsReportTransactions
import com.allcheck.operations.coordination.application.OperationsService
import com.allcheck.operations.coordination.application.OperationsTransactions
import com.allcheck.operations.coordination.presentation.OperationsId
import com.allcheck.operations.coordination.presentation.api.OperationsApi
import com.allcheck.operations.coordination.presentation.dto.request.CancelWorkRequest
import com.allcheck.operations.coordination.presentation.dto.request.FollowUpDecisionRequest
import com.allcheck.operations.coordination.presentation.dto.request.RegisterServiceRequest
import com.allcheck.operations.coordination.presentation.dto.request.RespondOfferRequest
import com.allcheck.operations.coordination.presentation.dto.request.ReviseReportRequest
import com.allcheck.operations.coordination.presentation.dto.request.RevisitPlanRequest
import com.allcheck.operations.coordination.presentation.dto.request.SubmitReportRequest
import com.allcheck.operations.coordination.presentation.dto.request.TransitionWorkRequest
import com.allcheck.operations.coordination.presentation.dto.request.WorkPhotoUploadRequest
import com.allcheck.operations.coordination.presentation.dto.response.OperationResponse
import com.allcheck.operations.coordination.presentation.dto.response.PhotoUploadResponse
import com.allcheck.operations.coordination.presentation.security.OperationsActorResolver
import org.springframework.http.CacheControl
import org.springframework.http.ResponseEntity
import org.springframework.security.access.prepost.PreAuthorize
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
    override fun reports(principal: AuthPrincipal?, page: Int, size: Int) = ok(queries.reports(actors.resolve(principal), page, size))
    override fun cancellations(principal: AuthPrincipal?, page: Int, size: Int) = ok(queries.cancellations(actors.resolve(principal), page, size))
    override fun cancellation(principal: AuthPrincipal?, cancellationId: String) = ok(queries.cancellation(actors.resolve(principal), OperationsId.CANCELLATION.parse(cancellationId)))
    override fun register(principal: AuthPrincipal?, key: String, body: RegisterServiceRequest) = command(service.register(actors.resolve(principal), OperationsId.RECEPTION.parse(body.receptionId), body.expectedVersion, key), OperationsId.REQUEST, OperationsId.ROUND, "/api/v1/service-requests")
    override fun request(principal: AuthPrincipal?, requestId: String) = ok(queries.request(actors.resolve(principal), OperationsId.REQUEST.parse(requestId)))
    override fun history(principal: AuthPrincipal?, requestId: String) = ok(queries.history(actors.resolve(principal), OperationsId.REQUEST.parse(requestId)))
    override fun rounds(principal: AuthPrincipal?, requestId: String) = ok(queries.history(actors.resolve(principal), OperationsId.REQUEST.parse(requestId)).rounds)
    override fun decide(principal: AuthPrincipal?, requestId: String, key: String, body: FollowUpDecisionRequest) = command(transactions.decide(actors.resolve(principal), OperationsId.REQUEST.parse(requestId), key, body.expectedVersion, body.target.target(), body.decision), OperationsId.DECISION, OperationsId.ROUND)
    override fun offers(principal: AuthPrincipal?, page: Int, size: Int) = ok(queries.offers(actors.resolve(principal), page, size))
    override fun offer(principal: AuthPrincipal?, offerId: String) = ok(queries.offer(actors.resolve(principal), OperationsId.OFFER.parse(offerId)))
    override fun respond(principal: AuthPrincipal?, offerId: String, key: String, body: RespondOfferRequest) = command(service.respond(actors.resolve(principal), OperationsId.OFFER.parse(offerId), key, body.response == "ACCEPT"), if (body.response == "ACCEPT") OperationsId.WORK else OperationsId.OFFER, OperationsId.OFFER)
    override fun received(principal: AuthPrincipal?, offerId: String): ResponseEntity<Void> {
        transactions.signal(actors.resolve(principal), OperationsId.OFFER.parse(offerId), false)
        return ResponseEntity.noContent().build()
    }
    override fun viewed(principal: AuthPrincipal?, offerId: String): ResponseEntity<Void> {
        transactions.signal(actors.resolve(principal), OperationsId.OFFER.parse(offerId), true)
        return ResponseEntity.noContent().build()
    }
    override fun works(principal: AuthPrincipal?, scope: String, month: String?, page: Int, size: Int) = ok(queries.driverWorks(actors.resolve(principal), scope, month, page, size))
    override fun statistics(principal: AuthPrincipal?, month: String) = ok(queries.statistics(actors.resolve(principal), month))
    override fun work(principal: AuthPrincipal?, workId: String) = ok(queries.work(actors.resolve(principal), OperationsId.WORK.parse(workId)))

    @PreAuthorize("hasRole('DRIVER')")
    override fun transition(principal: AuthPrincipal?, workId: String, key: String, body: TransitionWorkRequest) = command(transactions.transition(actors.resolve(principal), OperationsId.WORK.parse(workId), key, body.expectedVersion, body.targetStatus, body.reason, body.plannedRevisitAt), OperationsId.WORK)

    @PreAuthorize("hasRole('DRIVER')")
    override fun revisit(principal: AuthPrincipal?, workId: String, key: String, body: RevisitPlanRequest) = command(transactions.revisit(actors.resolve(principal), OperationsId.WORK.parse(workId), key, body.expectedVersion, body.plannedRevisitAt), OperationsId.WORK)

    @PreAuthorize("hasRole('DRIVER')")
    override fun cancel(principal: AuthPrincipal?, workId: String, key: String, body: CancelWorkRequest) = command(transactions.cancel(actors.resolve(principal), OperationsId.WORK.parse(workId), key, body.expectedVersion, body.reasonCode, body.reasonDetail), OperationsId.CANCELLATION, OperationsId.WORK, "/api/v1/dispatch-cancellations")

    @PreAuthorize("hasRole('DRIVER')")
    override fun submit(principal: AuthPrincipal?, workId: String, key: String, body: SubmitReportRequest) = command(reports.submit(actors.resolve(principal), OperationsId.WORK.parse(workId), key, body.expectedVersion, ReportContent(body.workDescription, body.diagnosis, body.totalCost), body.beforePhotoIds.map(OperationsId.PHOTO::parse), body.afterPhotoIds.map(OperationsId.PHOTO::parse)), OperationsId.REPORT, OperationsId.WORK, "/api/v1/reports")
    override fun report(principal: AuthPrincipal?, reportId: String) = ok(queries.report(actors.resolve(principal), OperationsId.REPORT.parse(reportId)))

    @PreAuthorize("hasRole('ADMIN')")
    override fun revise(principal: AuthPrincipal?, reportId: String, key: String, body: ReviseReportRequest) = command(reports.revise(actors.resolve(principal), OperationsId.REPORT.parse(reportId), key, body.expectedVersion, body.reason, body.changes.workDescription, body.changes.diagnosis, body.changes.totalCost), OperationsId.REPORT, OperationsId.WORK, "/api/v1/reports")

    @PreAuthorize("hasRole('DRIVER')")
    override fun reservePhoto(principal: AuthPrincipal?, workId: String, key: String, body: WorkPhotoUploadRequest): ResponseEntity<PhotoUploadResponse> {
        val (grant, replay) = photos.reserve(actors.resolve(principal), OperationsId.WORK.parse(workId), key, body.metadata())
        return ResponseEntity.status(201).cacheControl(CacheControl.noStore()).header("Idempotency-Replayed", replay.toString()).body(grant)
    }

    @PreAuthorize("hasRole('DRIVER')")
    override fun renewPhoto(principal: AuthPrincipal?, workId: String, photoId: String) = ResponseEntity.status(201).cacheControl(CacheControl.noStore()).body(photos.grant(actors.resolve(principal), OperationsId.WORK.parse(workId), OperationsId.PHOTO.parse(photoId)))

    @PreAuthorize("hasRole('DRIVER')")
    override fun completePhoto(principal: AuthPrincipal?, workId: String, photoId: String, key: String) = command(photos.complete(actors.resolve(principal), OperationsId.WORK.parse(workId), OperationsId.PHOTO.parse(photoId), key), OperationsId.PHOTO, OperationsId.WORK)
    override fun photoAccess(principal: AuthPrincipal?, workId: String, photoId: String) = ok(photos.access(actors.resolve(principal), OperationsId.WORK.parse(workId), OperationsId.PHOTO.parse(photoId)))
    private fun <T : Any> ok(value: T) = ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(value)
    private fun command(result: CommandResult, resource: OperationsId, related: OperationsId? = null, createdPath: String? = null): ResponseEntity<OperationResponse> {
        val outcome = result.value
        val response = OperationResponse(resource.format(outcome.resourceId), OperationsId.REQUEST.format(outcome.requestId), outcome.status, outcome.version, outcome.at, outcome.linkedId?.let { related?.format(it) })
        val builder = if (createdPath != null) ResponseEntity.created(URI.create("$createdPath/${response.resourceId}")) else ResponseEntity.ok()
        return builder.cacheControl(CacheControl.noStore()).header("Idempotency-Replayed", result.replayed.toString()).body(response)
    }
}
