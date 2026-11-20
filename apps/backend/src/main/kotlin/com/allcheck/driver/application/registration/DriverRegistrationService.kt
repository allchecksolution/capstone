package com.allcheck.driver.application.registration

import com.allcheck.driver.application.command.DriverCommandResult
import com.allcheck.driver.application.command.DriverIdempotencyService
import com.allcheck.driver.application.query.DriverQueryService
import com.allcheck.driver.domain.vo.registration.BusinessIdentity
import com.allcheck.driver.domain.vo.registration.BusinessOperatingStatus
import com.allcheck.driver.domain.vo.registration.VerifiedBusinessIdentity
import com.allcheck.driver.exception.DriverErrorCode
import com.allcheck.driver.infrastructure.registration.NtsBusinessClient
import com.allcheck.driver.presentation.dto.response.registration.DriverRegistrationResult
import com.allcheck.global.exception.BusinessException
import com.allcheck.global.ratelimit.RequestLimitPolicy
import com.allcheck.global.ratelimit.RequestLimitService
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.time.Duration

@Service
class DriverRegistrationService(
    private val verification: NtsBusinessClient,
    private val commands: DriverRegistrationTransactions,
    private val idempotency: DriverIdempotencyService,
    private val queries: DriverQueryService,
    private val limits: RequestLimitService,
) {
    /** 입력 단계의 확인 결과는 저장하지 않으며, 최종 신청 시 반드시 다시 검증한다. */
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    fun verifyBusiness(identity: BusinessIdentity): VerifiedBusinessIdentity {
        val verified = verification.verify(identity)
        if (verified.operatingStatus != BusinessOperatingStatus.CONTINUING) throw BusinessException(DriverErrorCode.BUSINESS_INFO_INVALID)
        return verified
    }

    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    fun submit(accountId: Long, key: String, request: RegisterDriverCommand): DriverCommandResult<DriverRegistrationResult> {
        limits.check(accountId.toString(), REGISTRATION_LIMIT)
        val previous = idempotency.known(accountId, "POST:/api/v1/drivers/me/registration", key, request, DriverRegistrationResult::class.java)
        if (previous != null) return previous
        queries.checkCanSubmit(accountId)
        val verified = verifyBusiness(request.business.identity())
        return commands.submit(accountId, key, request, verified)
    }

    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    fun resubmit(accountId: Long, key: String, request: ResubmitDriverCommand): DriverCommandResult<DriverRegistrationResult> {
        limits.check(accountId.toString(), REGISTRATION_LIMIT)
        val previous = idempotency.known(accountId, "POST:/api/v1/drivers/me/registration/resubmission", key, request, DriverRegistrationResult::class.java)
        if (previous != null) return previous
        queries.checkCanResubmit(accountId, request.expectedVersion)
        val verified = verifyBusiness(request.registration.business.identity())
        return commands.resubmit(accountId, key, request, verified)
    }

    companion object {
        private val REGISTRATION_LIMIT = RequestLimitPolicy("driver-registration", 5, Duration.ofMinutes(1))
    }
}
