package com.allcheck.identity.application.login

import com.allcheck.global.exception.BusinessException
import com.allcheck.identity.UserType
import com.allcheck.identity.application.session.SessionIssuanceService
import com.allcheck.identity.exception.IdentityErrorCode
import com.allcheck.identity.infrastructure.repository.account.AccountRepository
import com.allcheck.identity.presentation.dto.response.session.TokenPairResponse
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/** 비밀번호 해시가 자동 toString 등으로 노출되지 않게 일반 클래스로 보관한다. */
class AdminLoginCandidate(val accountId: Long, val loginId: String, val passwordHash: String)

@Service
@Transactional(readOnly = true)
class AdminSessionService(
    private val accounts: AccountRepository,
    private val issuance: SessionIssuanceService,
) {
    fun candidate(loginId: String): AdminLoginCandidate? {
        val account = accounts.findByAdminCredentialLoginId(loginId) ?: return null
        val credential = account.adminCredential ?: return null
        if (account.userType != UserType.ADMIN) return null
        return AdminLoginCandidate(checkNotNull(account.id), credential.loginId, credential.passwordHash)
    }

    @Transactional
    fun login(verified: AdminLoginCandidate): TokenPairResponse {
        val account = accounts.findByIdForUpdate(verified.accountId) ?: throw BusinessException(IdentityErrorCode.INVALID_CREDENTIALS)
        val credential = account.adminCredential ?: throw BusinessException(IdentityErrorCode.INVALID_CREDENTIALS)
        if (account.userType != UserType.ADMIN || credential.loginId != verified.loginId || credential.passwordHash != verified.passwordHash) {
            throw BusinessException(IdentityErrorCode.INVALID_CREDENTIALS)
        }
        return issuance.issue(account)
    }
}
