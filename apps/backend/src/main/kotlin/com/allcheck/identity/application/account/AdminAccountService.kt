package com.allcheck.identity.application.account

import com.allcheck.global.exception.BusinessException
import com.allcheck.identity.exception.IdentityErrorCode
import com.allcheck.identity.infrastructure.login.password.AdminPasswordHasher
import com.allcheck.identity.infrastructure.repository.account.AccountRepository
import com.allcheck.identity.presentation.dto.response.account.AuthUserResponse
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional

@Service
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class AdminAccountService(
    private val transactions: AdminAccountTransactions,
    private val accounts: AccountRepository,
    private val passwords: AdminPasswordHasher,
) {
    fun create(actorId: Long, loginId: String, password: String) = transactions.create(actorId, loginId, passwords.encode(password))

    fun update(actorId: Long, targetId: Long, version: Long, loginId: String?, password: String?) = transactions.update(actorId, targetId, version, loginId, password?.let(passwords::encode))

    fun changeOwnPassword(actorId: Long, currentPassword: String, newPassword: String) {
        val account = accounts.findById(actorId).orElseThrow { BusinessException(IdentityErrorCode.ACCOUNT_NOT_FOUND) }
        val hash = account.adminCredential?.passwordHash
        if (!passwords.matches(currentPassword, hash)) throw BusinessException(IdentityErrorCode.INVALID_CREDENTIALS)
        transactions.changeOwnPassword(actorId, checkNotNull(hash), passwords.encode(newPassword))
    }

    @Transactional(readOnly = true)
    fun me(actorId: Long): AuthUserResponse {
        val account = accounts.findById(actorId).orElseThrow { BusinessException(IdentityErrorCode.ACCOUNT_NOT_FOUND) }
        return AuthUserResponse(checkNotNull(account.id), account.userType, account.adminCredential?.role, account.isBootstrapAdmin.takeIf { account.adminCredential != null })
    }
}
