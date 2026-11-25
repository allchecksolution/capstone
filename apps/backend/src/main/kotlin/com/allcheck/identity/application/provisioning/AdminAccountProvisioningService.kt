package com.allcheck.identity.application.provisioning

import com.allcheck.global.exception.BusinessException
import com.allcheck.identity.AdminRole
import com.allcheck.identity.application.account.AdminAccountTransactions
import com.allcheck.identity.exception.IdentityErrorCode
import com.allcheck.identity.infrastructure.login.password.AdminPasswordHasher
import com.allcheck.identity.infrastructure.repository.account.AccountRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional

data class ProvisionedAdministrator(val accountId: Long, val loginId: String, val role: AdminRole)

/** 운영자 진입점에서만 사용하며 HTTP 계정 생성 경로로 노출하지 않는다. */
@Service
class AdminAccountProvisioningService(
    private val passwords: AdminPasswordHasher,
    private val transactions: AdminAccountProvisioningTransactions,
    private val administrators: AdminAccountTransactions,
    private val accounts: AccountRepository,
) {
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    fun provision(loginId: String, password: String, role: AdminRole): ProvisionedAdministrator {
        require(loginId.isNotBlank() && loginId.length <= 100 && '\u0000' !in loginId) { "Invalid administrator login ID." }
        val hash = passwords.encode(password)
        return transactions.create(loginId, hash, role)
    }

    fun recover(loginId: String, password: String): ProvisionedAdministrator {
        val account = administrators.recover(loginId, passwords.encode(password))
        return ProvisionedAdministrator(checkNotNull(account.id), loginId, checkNotNull(account.adminCredential).role)
    }

    fun adopt(loginId: String, password: String): ProvisionedAdministrator {
        val candidate = accounts.findByAdminCredentialLoginId(loginId)
        val hash = candidate?.adminCredential?.passwordHash
        if (!passwords.matches(password, hash)) throw BusinessException(IdentityErrorCode.INVALID_CREDENTIALS)
        val account = administrators.adopt(loginId, checkNotNull(hash))
        return ProvisionedAdministrator(checkNotNull(account.id), loginId, checkNotNull(account.adminCredential).role)
    }
}
