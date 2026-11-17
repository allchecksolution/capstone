package com.allcheck.driver.application

import com.allcheck.driver.domain.vo.BusinessOperatingStatus
import com.allcheck.driver.exception.DriverErrorCode
import com.allcheck.driver.infrastructure.business.NtsBusinessClient
import com.allcheck.driver.presentation.dto.request.DriverRegistrationRequest
import com.allcheck.driver.presentation.dto.request.DriverResubmissionRequest
import com.allcheck.driver.presentation.dto.response.DriverCommandResult
import com.allcheck.driver.presentation.dto.response.DriverRegistrationResult
import com.allcheck.global.exception.BusinessException
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional

@Service
class DriverRegistrationService(
    private val verification: NtsBusinessClient,
    private val commands: DriverCommandService,
    private val idempotency: DriverIdempotencyService,
    private val queries: DriverQueryService,
) {
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    fun submit(accountId: String, key: String, request: DriverRegistrationRequest): DriverCommandResult<DriverRegistrationResult> {
        val previous = idempotency.known(accountId, "POST:/api/v1/drivers/me/registration", key, request, DriverRegistrationResult::class.java)
        if (previous != null) return previous
        queries.checkCanSubmit(accountId)
        val verified = verification.verify(request.business.identity())
        if (verified.operatingStatus != BusinessOperatingStatus.CONTINUING) throw BusinessException(DriverErrorCode.BUSINESS_INFO_INVALID)
        return commands.submit(accountId, key, request, verified)
    }

    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    fun resubmit(accountId: String, key: String, request: DriverResubmissionRequest): DriverCommandResult<DriverRegistrationResult> {
        val previous = idempotency.known(accountId, "POST:/api/v1/drivers/me/registration/resubmission", key, request, DriverRegistrationResult::class.java)
        if (previous != null) return previous
        queries.checkCanResubmit(accountId, request.expectedVersion)
        val verified = verification.verify(request.registration.business.identity())
        if (verified.operatingStatus != BusinessOperatingStatus.CONTINUING) throw BusinessException(DriverErrorCode.BUSINESS_INFO_INVALID)
        return commands.resubmit(accountId, key, request, verified)
    }
}
