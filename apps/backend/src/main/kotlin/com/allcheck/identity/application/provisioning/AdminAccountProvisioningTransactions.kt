package com.allcheck.identity.application.provisioning

import com.allcheck.global.exception.BusinessException
import com.allcheck.identity.AdminRole
import com.allcheck.identity.domain.entity.account.Account
import com.allcheck.identity.domain.vo.account.AdminCredential
import com.allcheck.identity.exception.IdentityErrorCode
import com.allcheck.identity.infrastructure.repository.account.AccountRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock

@Service
@Transactional(readOnly = true)
class AdminAccountProvisioningTransactions(
    private val accounts: AccountRepository,
    private val clock: Clock = Clock.systemUTC(),
) {
    @Transactional
    fun create(loginId: String, passwordHash: String, role: AdminRole): ProvisionedAdministrator {
        val account = Account.administrator(AdminCredential(loginId, passwordHash, role), clock.instant())
        if (accounts.insertAdministratorIfAbsent(loginId, passwordHash, role.name, account.createdAt) == 0) {
            throw BusinessException(IdentityErrorCode.ADMIN_LOGIN_ID_ALREADY_EXISTS)
        }
        val created = checkNotNull(accounts.findByAdminCredentialLoginId(loginId))
        return ProvisionedAdministrator(checkNotNull(created.id), loginId, role)
    }
}
