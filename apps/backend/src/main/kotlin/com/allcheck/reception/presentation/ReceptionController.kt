package com.allcheck.reception.presentation

import com.allcheck.reception.application.ReceptionCommandService
import com.allcheck.reception.application.ReceptionQueryService
import com.allcheck.reception.domain.vo.ReceptionExternalId
import com.allcheck.reception.domain.vo.ReceptionReviewStatus
import com.allcheck.reception.presentation.api.ReceptionApi
import com.allcheck.reception.presentation.dto.request.ConfirmReceptionRequest
import com.allcheck.reception.presentation.dto.request.CreateReceptionRequest
import com.allcheck.reception.presentation.dto.request.ReopenReceptionRequest
import com.allcheck.reception.presentation.dto.response.ReceptionConfirmedResponse
import com.allcheck.reception.presentation.dto.response.ReceptionDetailResponse
import com.allcheck.reception.presentation.dto.response.ReceptionReopenedResponse
import org.springframework.http.CacheControl
import org.springframework.http.ResponseEntity
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.security.core.Authentication
import org.springframework.web.bind.annotation.RestController
import java.net.URI

@RestController
@PreAuthorize("hasRole('ADMIN')")
class ReceptionController(
    private val commands: ReceptionCommandService,
    private val queries: ReceptionQueryService,
    private val admins: ReceptionAdminResolver,
) : ReceptionApi {
    override fun create(authentication: Authentication, key: String, request: CreateReceptionRequest): ResponseEntity<ReceptionDetailResponse> {
        val result = commands.create(admins.resolve(authentication), key, request.toCreationData())
        return ResponseEntity.created(URI.create("/api/v1/receptions/${result.value.receptionId}"))
            .cacheControl(CacheControl.noStore()).header("Idempotency-Replayed", result.replayed.toString()).body(result.value)
    }

    override fun list(authentication: Authentication, reviewStatus: ReceptionReviewStatus?, page: Int, size: Int) = ResponseEntity.ok()
        .cacheControl(CacheControl.noStore()).body(queries.list(admins.resolve(authentication), reviewStatus, page, size))

    override fun detail(authentication: Authentication, receptionId: String) = ResponseEntity.ok()
        .cacheControl(CacheControl.noStore()).body(queries.detail(admins.resolve(authentication), ReceptionExternalId.RECEPTION.parse(receptionId)))

    override fun analysis(authentication: Authentication, receptionId: String, analysisRequestId: String) = ResponseEntity.ok()
        .cacheControl(CacheControl.noStore()).body(queries.analysis(admins.resolve(authentication), ReceptionExternalId.RECEPTION.parse(receptionId), ReceptionExternalId.ANALYSIS.parse(analysisRequestId)))

    override fun confirm(authentication: Authentication, receptionId: String, key: String, request: ConfirmReceptionRequest): ResponseEntity<ReceptionConfirmedResponse> {
        val result = commands.confirm(admins.resolve(authentication), ReceptionExternalId.RECEPTION.parse(receptionId), key, request.expectedVersion, request.finalData.toConfirmationData())
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).header("Idempotency-Replayed", result.replayed.toString()).body(result.value)
    }

    override fun reopen(authentication: Authentication, receptionId: String, key: String, request: ReopenReceptionRequest): ResponseEntity<ReceptionReopenedResponse> {
        val result = commands.reopen(admins.resolve(authentication), ReceptionExternalId.RECEPTION.parse(receptionId), key, request.expectedVersion)
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).header("Idempotency-Replayed", result.replayed.toString()).body(result.value)
    }
}
