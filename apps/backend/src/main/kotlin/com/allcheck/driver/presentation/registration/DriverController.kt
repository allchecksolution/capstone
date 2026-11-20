package com.allcheck.driver.presentation.registration

import com.allcheck.driver.application.command.DriverCommandResult
import com.allcheck.driver.application.query.DriverQueryService
import com.allcheck.driver.application.registration.DriverRegistrationService
import com.allcheck.driver.application.review.DriverReviewService
import com.allcheck.driver.application.withdrawal.DriverWithdrawalService
import com.allcheck.driver.domain.vo.registration.BusinessIdentity
import com.allcheck.driver.domain.vo.registration.DriverAccountStatus
import com.allcheck.driver.presentation.api.registration.DriverApi
import com.allcheck.driver.presentation.dto.request.registration.BusinessVerificationRequest
import com.allcheck.driver.presentation.dto.request.registration.DriverRegistrationRequest
import com.allcheck.driver.presentation.dto.request.registration.DriverResubmissionRequest
import com.allcheck.driver.presentation.dto.request.review.DriverApprovalRequest
import com.allcheck.driver.presentation.dto.request.withdrawal.DriverWithdrawalRequest
import com.allcheck.driver.presentation.dto.response.query.DriverDetailResponse
import com.allcheck.driver.presentation.dto.response.query.DriverListResponse
import com.allcheck.driver.presentation.dto.response.registration.BusinessVerificationResponse
import com.allcheck.driver.presentation.dto.response.registration.DriverRegistrationResult
import com.allcheck.driver.presentation.dto.response.registration.DriverRegistrationView
import com.allcheck.driver.presentation.dto.response.review.DriverApprovalResult
import com.allcheck.driver.presentation.dto.response.withdrawal.DriverWithdrawalResult
import com.allcheck.global.security.principal.AuthPrincipal
import org.springframework.http.CacheControl
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.RestController

@RestController
class DriverController(
    private val registration: DriverRegistrationService,
    private val queries: DriverQueryService,
    private val reviews: DriverReviewService,
    private val withdrawals: DriverWithdrawalService,
) : DriverApi {
    override fun verifyBusiness(principal: AuthPrincipal, request: BusinessVerificationRequest): ResponseEntity<BusinessVerificationResponse> {
        val verified = registration.verifyBusiness(BusinessIdentity(request.businessRegistrationNumber, request.openedOn, request.representativeName))
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(BusinessVerificationResponse(verifiedAt = verified.verifiedAt))
    }

    override fun mine(principal: AuthPrincipal): ResponseEntity<DriverRegistrationView> = ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(queries.mine(principal.accountId))

    override fun submit(principal: AuthPrincipal, key: String, request: DriverRegistrationRequest): ResponseEntity<DriverRegistrationResult> = command(registration.submit(principal.accountId, key, request.toCommand()), HttpStatus.CREATED)

    override fun resubmit(principal: AuthPrincipal, key: String, request: DriverResubmissionRequest): ResponseEntity<DriverRegistrationResult> = command(registration.resubmit(principal.accountId, key, request.toCommand()))

    override fun list(accountStatus: DriverAccountStatus?, q: String?, page: Int, size: Int): ResponseEntity<DriverListResponse> = ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(queries.list(accountStatus, q, page, size))

    override fun detail(driverId: Long): ResponseEntity<DriverDetailResponse> = ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(queries.detail(driverId))

    override fun decide(principal: AuthPrincipal, driverId: Long, key: String, request: DriverApprovalRequest): ResponseEntity<DriverApprovalResult> = command(reviews.decide(principal.accountId, driverId, key, request.toCommand()))

    override fun withdraw(principal: AuthPrincipal, key: String, request: DriverWithdrawalRequest): ResponseEntity<DriverWithdrawalResult> = command(withdrawals.withdraw(principal.accountId, key, request.toCommand()))

    private fun <T : Any> command(result: DriverCommandResult<T>, status: HttpStatus = HttpStatus.OK): ResponseEntity<T> = ResponseEntity.status(status)
        .cacheControl(CacheControl.noStore())
        .header("Idempotency-Replayed", result.replayed.toString())
        .body(result.body)
}
