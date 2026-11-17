package com.allcheck.driver.application

import com.allcheck.driver.DriverWorkload
import com.allcheck.driver.domain.entity.Driver
import com.allcheck.driver.domain.entity.DriverHistory
import com.allcheck.driver.domain.entity.DriverNotificationEvent
import com.allcheck.driver.domain.vo.VerifiedBusinessIdentity
import com.allcheck.driver.exception.DriverErrorCode
import com.allcheck.driver.infrastructure.repository.DriverHistoryRepository
import com.allcheck.driver.infrastructure.repository.DriverNotificationEventRepository
import com.allcheck.driver.infrastructure.repository.DriverRepository
import com.allcheck.driver.presentation.dto.request.ApprovalDecision
import com.allcheck.driver.presentation.dto.request.DriverApprovalRequest
import com.allcheck.driver.presentation.dto.request.DriverRegistrationRequest
import com.allcheck.driver.presentation.dto.request.DriverResubmissionRequest
import com.allcheck.driver.presentation.dto.request.DriverWithdrawalRequest
import com.allcheck.driver.presentation.dto.response.DriverApprovalResult
import com.allcheck.driver.presentation.dto.response.DriverCommandResult
import com.allcheck.driver.presentation.dto.response.DriverRegistrationResult
import com.allcheck.driver.presentation.dto.response.DriverWithdrawalResult
import com.allcheck.global.exception.BusinessException
import com.allcheck.identity.IdentityAccountOperations
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import tools.jackson.databind.json.JsonMapper
import java.time.Clock

@Service
@Transactional(readOnly = true)
class DriverCommandService(
    private val drivers: DriverRepository,
    private val history: DriverHistoryRepository,
    private val events: DriverNotificationEventRepository,
    private val identity: IdentityAccountOperations,
    private val workload: DriverWorkload,
    private val idempotency: DriverIdempotencyService,
    private val mapper: JsonMapper,
    private val clock: Clock = Clock.systemUTC(),
) {
    @Transactional
    fun submit(accountId: String, key: String, request: DriverRegistrationRequest, verified: VerifiedBusinessIdentity): DriverCommandResult<DriverRegistrationResult> = idempotency.execute(accountId, "POST:/api/v1/drivers/me/registration", key, request, DriverRegistrationResult::class.java) {
        identity.lockActiveDriverAccount(accountId)
        if (drivers.existsByAccountId(accountId)) throw BusinessException(DriverErrorCode.DRIVER_ALREADY_REGISTERED)
        val now = clock.instant()
        val driver = drivers.saveAndFlush(Driver(accountId, request.profile.entity(), request.business.entity(verified), now))
        history.save(DriverHistory(driver.id, driver.revision, "SUBMITTED", accountId, now, requestSnapshot = mapper.writeValueAsString(request)))
        events.save(DriverNotificationEvent(driver.id, driver.revision, "REGISTRATION_SUBMITTED", null, now))
        driver.result()
    }

    @Transactional
    fun resubmit(accountId: String, key: String, request: DriverResubmissionRequest, verified: VerifiedBusinessIdentity): DriverCommandResult<DriverRegistrationResult> = idempotency.execute(accountId, "POST:/api/v1/drivers/me/registration/resubmission", key, request, DriverRegistrationResult::class.java) {
        identity.lockActiveDriverAccount(accountId)
        val driver = drivers.findByAccountIdForUpdate(accountId) ?: throw BusinessException(DriverErrorCode.DRIVER_NOT_FOUND)
        driver.checkVersion(request.expectedVersion)
        val now = clock.instant()
        driver.resubmit(request.registration.profile.entity(), request.registration.business.entity(verified), now)
        history.save(DriverHistory(driver.id, driver.revision, "SUBMITTED", accountId, now, requestSnapshot = mapper.writeValueAsString(request.registration)))
        events.save(DriverNotificationEvent(driver.id, driver.revision, "REGISTRATION_SUBMITTED", null, now))
        drivers.flush()
        driver.result()
    }

    @Transactional
    fun decide(actor: String, driverId: String, key: String, request: DriverApprovalRequest): DriverCommandResult<DriverApprovalResult> = idempotency.execute(actor, "POST:/api/v1/drivers/$driverId/approval", key, request, DriverApprovalResult::class.java) {
        val accountId = drivers.findAccountId(driverId) ?: throw BusinessException(DriverErrorCode.DRIVER_NOT_FOUND)
        identity.lockActiveDriverAccount(accountId)
        val driver = drivers.findByIdForUpdate(driverId) ?: throw BusinessException(DriverErrorCode.DRIVER_NOT_FOUND)
        driver.checkVersion(request.expectedVersion)
        val now = clock.instant()
        val approved = request.decision == ApprovalDecision.APPROVE
        if (approved) driver.approve() else driver.reject(checkNotNull(request.rejectionReason), now)
        val eventType = if (approved) "APPROVED" else "REJECTED"
        history.save(DriverHistory(driver.id, driver.revision, eventType, actor, now, reason = if (approved) null else request.rejectionReason))
        events.save(DriverNotificationEvent(driver.id, driver.revision, "DRIVER_$eventType", driver.accountId, now))
        drivers.flush()
        DriverApprovalResult(driver.id, driver.accountStatus, driver.version, now)
    }

    @Transactional
    fun withdraw(accountId: String, key: String, request: DriverWithdrawalRequest): DriverCommandResult<DriverWithdrawalResult> = idempotency.execute(accountId, "POST:/api/v1/drivers/me/withdrawal", key, request, DriverWithdrawalResult::class.java) {
        identity.lockActiveDriverAccount(accountId)
        val driver = drivers.findByAccountIdForUpdate(accountId) ?: throw BusinessException(DriverErrorCode.DRIVER_NOT_FOUND)
        driver.checkVersion(request.expectedVersion)
        driver.requireActive()
        if (workload.hasUnfinishedWork(driver.id)) throw BusinessException(DriverErrorCode.WITHDRAWAL_BLOCKED_ACTIVE_WORK)
        val now = clock.instant()
        driver.withdraw(now)
        identity.deactivateDriverAccount(accountId)
        history.save(DriverHistory(driver.id, driver.revision, "WITHDRAWN", accountId, now))
        drivers.flush()
        DriverWithdrawalResult(driver.id, driver.accountStatus, now)
    }
}
