package com.allcheck.identity.application.provisioning

import com.allcheck.identity.AdminRole
import com.allcheck.identity.infrastructure.login.password.AdminPasswordHasher
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional

data class ProvisionedAdministrator(val accountId: Long, val loginId: String, val role: AdminRole)

/** 운영자 진입점에서만 사용하며 HTTP 계정 생성 경로로 노출하지 않는다. */
@Service
class AdminAccountProvisioningService(
    private val passwords: AdminPasswordHasher,
    private val transactions: AdminAccountProvisioningTransactions,
) {
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    fun provision(loginId: String, password: String, role: AdminRole): ProvisionedAdministrator {
        require(loginId.isNotBlank() && loginId.length <= 100 && '\u0000' !in loginId) { "Invalid administrator login ID." }
        val hash = passwords.encode(password)
        return transactions.create(loginId, hash, role)
    }
}
