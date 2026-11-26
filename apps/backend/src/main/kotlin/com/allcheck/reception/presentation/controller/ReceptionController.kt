package com.allcheck.reception.presentation.controller

import com.allcheck.global.exception.BusinessException
import com.allcheck.global.security.exception.SecurityErrorCode
import com.allcheck.global.security.principal.AuthPrincipal
import com.allcheck.identity.UserType
import com.allcheck.reception.application.ReceptionCommandService
import com.allcheck.reception.application.review.ReceptionQueryService
import com.allcheck.reception.domain.vo.ReceptionExternalId
import com.allcheck.reception.domain.vo.review.ReceptionReviewStatus
import com.allcheck.reception.presentation.api.ReceptionApi
import com.allcheck.reception.presentation.dto.request.review.ConfirmReceptionRequest
import com.allcheck.reception.presentation.dto.request.review.CreateReceptionRequest
import com.allcheck.reception.presentation.dto.request.review.ReopenReceptionRequest
import com.allcheck.reception.presentation.dto.response.review.ReceptionConfirmedResponse
import com.allcheck.reception.presentation.dto.response.review.ReceptionDetailResponse
import com.allcheck.reception.presentation.dto.response.review.ReceptionReopenedResponse
import org.springframework.http.CacheControl
import org.springframework.http.ResponseEntity
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.RestController
import java.net.URI

@RestController
@PreAuthorize("hasRole('ADMIN')")
class ReceptionController(
    private val commands: ReceptionCommandService,
    private val queries: ReceptionQueryService,
) : ReceptionApi {
    override fun create(principal: AuthPrincipal?, key: String, request: CreateReceptionRequest): ResponseEntity<ReceptionDetailResponse> {
        val result = commands.create(principal.requireReceptionAdminId(), key, request.toCreationData())
        return ResponseEntity.created(URI.create("/api/v1/receptions/${result.value.receptionId}"))
            .cacheControl(CacheControl.noStore()).header("Idempotency-Replayed", result.replayed.toString()).body(result.value)
    }

    override fun list(principal: AuthPrincipal?, reviewStatus: ReceptionReviewStatus?, page: Int, size: Int) = ResponseEntity.ok()
        .cacheControl(CacheControl.noStore()).body(queries.list(principal.requireReceptionAdminId(), reviewStatus, page, size))

    override fun detail(principal: AuthPrincipal?, receptionId: String) = ResponseEntity.ok()
        .cacheControl(CacheControl.noStore()).body(queries.detail(principal.requireReceptionAdminId(), ReceptionExternalId.RECEPTION.parse(receptionId)))

    override fun analysis(principal: AuthPrincipal?, receptionId: String, analysisRequestId: String) = ResponseEntity.ok()
        .cacheControl(CacheControl.noStore()).body(queries.analysis(principal.requireReceptionAdminId(), ReceptionExternalId.RECEPTION.parse(receptionId), ReceptionExternalId.ANALYSIS.parse(analysisRequestId)))

    override fun confirm(principal: AuthPrincipal?, receptionId: String, key: String, request: ConfirmReceptionRequest): ResponseEntity<ReceptionConfirmedResponse> {
        val result = commands.confirm(principal.requireReceptionAdminId(), ReceptionExternalId.RECEPTION.parse(receptionId), key, request.expectedVersion, request.finalData.toConfirmationData())
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).header("Idempotency-Replayed", result.replayed.toString()).body(result.value)
    }

    override fun reopen(principal: AuthPrincipal?, receptionId: String, key: String, request: ReopenReceptionRequest): ResponseEntity<ReceptionReopenedResponse> {
        val result = commands.reopen(principal.requireReceptionAdminId(), ReceptionExternalId.RECEPTION.parse(receptionId), key, request.expectedVersion)
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).header("Idempotency-Replayed", result.replayed.toString()).body(result.value)
    }
}

internal fun AuthPrincipal?.requireReceptionAdminId(): Long {
    if (this == null || userType != UserType.ADMIN) throw BusinessException(SecurityErrorCode.ACCESS_DENIED)
    check(accountId > 0) { "The authenticated administrator must have a persisted account ID." }
    return accountId
}
