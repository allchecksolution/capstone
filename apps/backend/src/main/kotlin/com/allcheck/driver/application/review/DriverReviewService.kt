package com.allcheck.driver.application.review

import com.allcheck.driver.application.command.DriverCommandResult
import com.allcheck.driver.application.command.DriverIdempotencyService
import com.allcheck.driver.application.review.ReviewDriverCommand
import com.allcheck.driver.domain.entity.notification.DriverNotificationEvent
import com.allcheck.driver.domain.entity.registration.DriverHistory
import com.allcheck.driver.domain.vo.review.ApprovalDecision
import com.allcheck.driver.exception.DriverErrorCode
import com.allcheck.driver.infrastructure.repository.notification.DriverNotificationEventRepository
import com.allcheck.driver.infrastructure.repository.registration.DriverHistoryRepository
import com.allcheck.driver.infrastructure.repository.registration.DriverRepository
import com.allcheck.driver.presentation.dto.response.review.DriverApprovalResult
import com.allcheck.global.exception.BusinessException
import com.allcheck.identity.IdentityAccountOperations
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock

@Service
@Transactional(readOnly = true)
class DriverReviewService(
    private val drivers: DriverRepository,
    private val history: DriverHistoryRepository,
    private val events: DriverNotificationEventRepository,
    private val identity: IdentityAccountOperations,
    private val idempotency: DriverIdempotencyService,
    private val clock: Clock = Clock.systemUTC(),
) {
    @Transactional
    fun decide(actor: Long, driverId: Long, key: String, request: ReviewDriverCommand): DriverCommandResult<DriverApprovalResult> = idempotency.execute(actor, "POST:/api/v1/drivers/$driverId/approval", key, request, DriverApprovalResult::class.java) {
        val accountId = drivers.findAccountId(driverId) ?: throw BusinessException(DriverErrorCode.DRIVER_NOT_FOUND)
        identity.lockActiveDriverAccount(accountId)
        val driver = drivers.findByIdForUpdate(driverId) ?: throw BusinessException(DriverErrorCode.DRIVER_NOT_FOUND)
        driver.checkVersion(request.expectedVersion)
        val now = clock.instant()
        val approved = request.decision == ApprovalDecision.APPROVE
        if (approved) driver.approve() else driver.reject(checkNotNull(request.rejectionReason), now)
        val eventType = if (approved) "APPROVED" else "REJECTED"
        history.save(DriverHistory(checkNotNull(driver.id), driver.revision, eventType, actor, now, reason = if (approved) null else request.rejectionReason))
        events.save(DriverNotificationEvent(checkNotNull(driver.id), driver.revision, "DRIVER_$eventType", driver.accountId, now))
        drivers.flush()
        DriverApprovalResult(checkNotNull(driver.id), driver.accountStatus, driver.version, now)
    }
}
