package com.allcheck.driver.application.access

import com.allcheck.driver.DriverIdentity
import com.allcheck.driver.DriverOperations
import com.allcheck.driver.domain.vo.registration.DriverAccountStatus
import com.allcheck.driver.exception.DriverErrorCode
import com.allcheck.driver.infrastructure.repository.registration.DriverRepository
import com.allcheck.global.exception.BusinessException
import com.allcheck.identity.IdentityAccountOperations
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional

@Service
@Transactional(readOnly = true)
class DriverAccessService(
    private val drivers: DriverRepository,
    private val identity: IdentityAccountOperations,
) : DriverOperations {
    override fun isActiveDriverAccount(accountId: String): Boolean = drivers.existsByAccountIdAndAccountStatus(accountId, DriverAccountStatus.ACTIVATED)

    @Transactional(propagation = Propagation.MANDATORY)
    override fun lockActiveDriver(driverId: String): DriverIdentity {
        val accountId = drivers.findAccountId(driverId) ?: throw BusinessException(DriverErrorCode.DRIVER_NOT_FOUND)
        identity.lockActiveDriverAccount(accountId)
        val driver = drivers.findByIdForUpdate(driverId) ?: throw BusinessException(DriverErrorCode.DRIVER_NOT_FOUND)
        driver.requireActive()
        return DriverIdentity(driver.id, driver.accountId)
    }
}
