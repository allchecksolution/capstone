package com.allcheck.identity.application.provisioning

import com.allcheck.identity.AdminRole
import com.allcheck.identity.application.account.AdminAccountTransactions
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
@Transactional(readOnly = true)
class AdminAccountProvisioningTransactions(
    private val administrators: AdminAccountTransactions,
) {
    @Transactional
    fun create(loginId: String, passwordHash: String, role: AdminRole): ProvisionedAdministrator {
        val created = administrators.bootstrap(loginId, passwordHash, role)
        return ProvisionedAdministrator(checkNotNull(created.id), loginId, role)
    }
}
