package com.allcheck.driver.application.registration

import com.allcheck.driver.application.command.DriverCommandResult
import com.allcheck.driver.application.command.DriverIdempotencyService
import com.allcheck.driver.application.registration.RegisterDriverCommand
import com.allcheck.driver.application.registration.ResubmitDriverCommand
import com.allcheck.driver.domain.entity.notification.DriverNotificationEvent
import com.allcheck.driver.domain.entity.registration.Driver
import com.allcheck.driver.domain.entity.registration.DriverHistory
import com.allcheck.driver.domain.vo.registration.VerifiedBusinessIdentity
import com.allcheck.driver.exception.DriverErrorCode
import com.allcheck.driver.infrastructure.repository.notification.DriverNotificationEventRepository
import com.allcheck.driver.infrastructure.repository.registration.DriverHistoryRepository
import com.allcheck.driver.infrastructure.repository.registration.DriverRepository
import com.allcheck.driver.presentation.dto.response.registration.DriverRegistrationResult
import com.allcheck.global.exception.BusinessException
import com.allcheck.identity.IdentityAccountOperations
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import tools.jackson.databind.json.JsonMapper
import java.time.Clock

@Service
@Transactional(readOnly = true)
class DriverRegistrationTransactions(
    private val drivers: DriverRepository,
    private val history: DriverHistoryRepository,
    private val events: DriverNotificationEventRepository,
    private val identity: IdentityAccountOperations,
    private val idempotency: DriverIdempotencyService,
    private val mapper: JsonMapper,
    private val clock: Clock = Clock.systemUTC(),
) {
    @Transactional
    fun submit(accountId: Long, key: String, request: RegisterDriverCommand, verified: VerifiedBusinessIdentity): DriverCommandResult<DriverRegistrationResult> = idempotency.execute(accountId, "POST:/api/v1/drivers/me/registration", key, request, DriverRegistrationResult::class.java) {
        identity.lockActiveDriverAccount(accountId)
        if (drivers.existsByAccountId(accountId)) throw BusinessException(DriverErrorCode.DRIVER_ALREADY_REGISTERED)
        val now = clock.instant()
        val driver = drivers.saveAndFlush(Driver(accountId, request.profile.toProfile(), request.business.entity(verified), now))
        history.save(DriverHistory(checkNotNull(driver.id), driver.revision, "SUBMITTED", accountId, now, requestSnapshot = mapper.writeValueAsString(request)))
        events.save(DriverNotificationEvent(checkNotNull(driver.id), driver.revision, "REGISTRATION_SUBMITTED", null, now))
        driver.result()
    }

    @Transactional
    fun resubmit(accountId: Long, key: String, request: ResubmitDriverCommand, verified: VerifiedBusinessIdentity): DriverCommandResult<DriverRegistrationResult> = idempotency.execute(accountId, "POST:/api/v1/drivers/me/registration/resubmission", key, request, DriverRegistrationResult::class.java) {
        identity.lockActiveDriverAccount(accountId)
        val driver = drivers.findByAccountIdForUpdate(accountId) ?: throw BusinessException(DriverErrorCode.DRIVER_NOT_FOUND)
        driver.checkVersion(request.expectedVersion)
        val now = clock.instant()
        driver.resubmit(request.registration.profile.toProfile(), request.registration.business.entity(verified), now)
        history.save(DriverHistory(checkNotNull(driver.id), driver.revision, "SUBMITTED", accountId, now, requestSnapshot = mapper.writeValueAsString(request.registration)))
        events.save(DriverNotificationEvent(checkNotNull(driver.id), driver.revision, "REGISTRATION_SUBMITTED", null, now))
        drivers.flush()
        driver.result()
    }
}
