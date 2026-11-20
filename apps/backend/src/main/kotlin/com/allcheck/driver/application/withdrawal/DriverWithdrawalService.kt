package com.allcheck.driver.application.withdrawal

import com.allcheck.driver.DriverWorkload
import com.allcheck.driver.application.command.DriverCommandResult
import com.allcheck.driver.application.command.DriverIdempotencyService
import com.allcheck.driver.application.withdrawal.WithdrawDriverCommand
import com.allcheck.driver.domain.entity.registration.DriverHistory
import com.allcheck.driver.exception.DriverErrorCode
import com.allcheck.driver.infrastructure.repository.registration.DriverHistoryRepository
import com.allcheck.driver.infrastructure.repository.registration.DriverRepository
import com.allcheck.driver.presentation.dto.response.withdrawal.DriverWithdrawalResult
import com.allcheck.global.exception.BusinessException
import com.allcheck.identity.IdentityAccountOperations
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock

@Service
@Transactional(readOnly = true)
class DriverWithdrawalService(
    private val drivers: DriverRepository,
    private val history: DriverHistoryRepository,
    private val identity: IdentityAccountOperations,
    private val workload: DriverWorkload,
    private val idempotency: DriverIdempotencyService,
    private val clock: Clock = Clock.systemUTC(),
) {
    @Transactional
    fun withdraw(accountId: Long, key: String, request: WithdrawDriverCommand): DriverCommandResult<DriverWithdrawalResult> = idempotency.execute(accountId, "POST:/api/v1/drivers/me/withdrawal", key, request, DriverWithdrawalResult::class.java) {
        identity.lockActiveDriverAccount(accountId)
        val driver = drivers.findByAccountIdForUpdate(accountId) ?: throw BusinessException(DriverErrorCode.DRIVER_NOT_FOUND)
        driver.checkVersion(request.expectedVersion)
        driver.requireActive()
        if (workload.hasUnfinishedWork(checkNotNull(driver.id))) throw BusinessException(DriverErrorCode.WITHDRAWAL_BLOCKED_ACTIVE_WORK)
        val now = clock.instant()
        driver.withdraw(now)
        identity.deactivateDriverAccount(accountId)
        history.save(DriverHistory(checkNotNull(driver.id), driver.revision, "WITHDRAWN", accountId, now))
        drivers.flush()
        DriverWithdrawalResult(checkNotNull(driver.id), driver.accountStatus, now)
    }
}
