package com.allcheck.identity.application.account

import com.allcheck.global.exception.BusinessException
import com.allcheck.identity.AuthenticatedAccount
import com.allcheck.identity.IdentityAccountOperations
import com.allcheck.identity.UserType
import com.allcheck.identity.domain.entity.account.Account
import com.allcheck.identity.exception.IdentityErrorCode
import com.allcheck.identity.infrastructure.repository.account.AccountRepository
import com.allcheck.identity.infrastructure.repository.device.PushDeviceRepository
import com.allcheck.identity.infrastructure.repository.session.AuthSessionRepository
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
    override fun activeDriverAccountIds(accountIds: Collection<Long>): Set<Long> = if (accountIds.isEmpty()) emptySet() else accountIds.chunked(1000).flatMap(accounts::activeDriverIds).toSet()

    @Transactional(propagation = Propagation.MANDATORY)
    override fun lockActiveDriverAccount(accountId: Long): AuthenticatedAccount {
        val account = lockDriverAccount(accountId)
        if (!account.enabled) throw BusinessException(IdentityErrorCode.ACCOUNT_DISABLED)
        return AuthenticatedAccount(checkNotNull(account.id), account.userType, null)
    }

    @Transactional
    override fun deactivateDriverAccount(accountId: Long) {
        val account = lockDriverAccount(accountId)
        val accountSessions = sessions.findAllByAccountIdForUpdate(checkNotNull(account.id))
        val now = clock.instant()
        account.disable(now)
        accountSessions.forEach { it.revoke(now) }
        devices.revokeAccount(checkNotNull(account.id), now)
    }

    private fun lockDriverAccount(accountId: Long): Account {
        val account = accounts.findByIdForUpdate(accountId) ?: throw BusinessException(IdentityErrorCode.ACCOUNT_NOT_FOUND)
        if (account.userType != UserType.DRIVER) throw BusinessException(IdentityErrorCode.DRIVER_ACCOUNT_REQUIRED)
        return account
    }
}
