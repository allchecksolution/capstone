package com.allcheck.identity.application

import com.allcheck.global.exception.BusinessException
import com.allcheck.identity.AuthenticatedAccount
import com.allcheck.identity.IdentityAccountOperations
import com.allcheck.identity.UserType
import com.allcheck.identity.domain.entity.Account
import com.allcheck.identity.exception.IdentityErrorCode
import com.allcheck.identity.infrastructure.repository.AccountRepository
import com.allcheck.identity.infrastructure.repository.AuthSessionRepository
import com.allcheck.identity.infrastructure.repository.PushDeviceRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.time.Clock

@Service
@Transactional(readOnly = true)
class DriverAccountLifecycleService(
    private val accounts: AccountRepository,
    private val sessions: AuthSessionRepository,
    private val devices: PushDeviceRepository,
    private val clock: Clock = Clock.systemUTC(),
) : IdentityAccountOperations {
    @Transactional(propagation = Propagation.MANDATORY)
    override fun lockActiveDriverAccount(accountId: String): AuthenticatedAccount {
        val account = lockDriverAccount(accountId)
        if (!account.enabled) throw BusinessException(IdentityErrorCode.ACCOUNT_DISABLED)
        return AuthenticatedAccount(account.id, account.userType, null)
    }

    @Transactional
    override fun deactivateDriverAccount(accountId: String) {
        val account = lockDriverAccount(accountId)
        val accountSessions = sessions.findAllByAccountIdForUpdate(account.id)
        val now = clock.instant()
        account.disable(now)
        accountSessions.forEach { it.revoke(now) }
        devices.revokeAccount(account.id, now)
    }

    private fun lockDriverAccount(accountId: String): Account {
        val account = accounts.findByIdForUpdate(accountId) ?: throw BusinessException(IdentityErrorCode.ACCOUNT_NOT_FOUND)
        if (account.userType != UserType.DRIVER) throw BusinessException(IdentityErrorCode.DRIVER_ACCOUNT_REQUIRED)
        return account
    }
}
