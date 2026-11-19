package com.allcheck.driver.presentation

import com.allcheck.driver.application.DriverCommandService
import com.allcheck.driver.application.DriverQueryService
import com.allcheck.driver.application.DriverRegistrationService
import com.allcheck.driver.domain.vo.BusinessIdentity
import com.allcheck.driver.domain.vo.DriverAccountStatus
import com.allcheck.driver.presentation.api.DriverApi
import com.allcheck.driver.presentation.dto.request.BusinessVerificationRequest
import com.allcheck.driver.presentation.dto.request.DriverApprovalRequest
import com.allcheck.driver.presentation.dto.request.DriverRegistrationRequest
import com.allcheck.driver.presentation.dto.request.DriverResubmissionRequest
import com.allcheck.driver.presentation.dto.request.DriverWithdrawalRequest
import com.allcheck.driver.presentation.dto.response.BusinessVerificationResponse
import com.allcheck.driver.presentation.dto.response.DriverApprovalResult
import com.allcheck.driver.presentation.dto.response.DriverCommandResult
import com.allcheck.driver.presentation.dto.response.DriverDetailResponse
import com.allcheck.driver.presentation.dto.response.DriverListResponse
import com.allcheck.driver.presentation.dto.response.DriverRegistrationResult
import com.allcheck.driver.presentation.dto.response.DriverRegistrationView
import com.allcheck.driver.presentation.dto.response.DriverWithdrawalResult
import com.allcheck.global.security.principal.AuthPrincipal
import org.springframework.http.CacheControl
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.RestController

@RestController
class DriverController(
    private val registration: DriverRegistrationService,
    private val queries: DriverQueryService,
    private val commands: DriverCommandService,
) : DriverApi {
    override fun verifyBusiness(principal: AuthPrincipal, request: BusinessVerificationRequest): ResponseEntity<BusinessVerificationResponse> {
        val verified = registration.verifyBusiness(BusinessIdentity(request.businessRegistrationNumber, request.openedOn, request.representativeName))
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(BusinessVerificationResponse(verifiedAt = verified.verifiedAt))
    }

    override fun mine(principal: AuthPrincipal): ResponseEntity<DriverRegistrationView> = ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(queries.mine(principal.accountId))

    override fun submit(principal: AuthPrincipal, key: String, request: DriverRegistrationRequest): ResponseEntity<DriverRegistrationResult> = command(registration.submit(principal.accountId, key, request), HttpStatus.CREATED)

    override fun resubmit(principal: AuthPrincipal, key: String, request: DriverResubmissionRequest): ResponseEntity<DriverRegistrationResult> = command(registration.resubmit(principal.accountId, key, request))

    override fun list(accountStatus: DriverAccountStatus?, q: String?, page: Int, size: Int): ResponseEntity<DriverListResponse> = ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(queries.list(accountStatus, q, page, size))

    override fun detail(driverId: String): ResponseEntity<DriverDetailResponse> = ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(queries.detail(driverId))

    override fun decide(principal: AuthPrincipal, driverId: String, key: String, request: DriverApprovalRequest): ResponseEntity<DriverApprovalResult> = command(commands.decide(principal.accountId, driverId, key, request))

    override fun withdraw(principal: AuthPrincipal, key: String, request: DriverWithdrawalRequest): ResponseEntity<DriverWithdrawalResult> = command(commands.withdraw(principal.accountId, key, request))

    private fun <T : Any> command(result: DriverCommandResult<T>, status: HttpStatus = HttpStatus.OK): ResponseEntity<T> = ResponseEntity.status(status)
        .cacheControl(CacheControl.noStore())
        .header("Idempotency-Replayed", result.replayed.toString())
        .body(result.body)
}
