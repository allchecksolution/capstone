package com.allcheck.identity.application.login

import com.allcheck.global.exception.BusinessException
import com.allcheck.identity.exception.IdentityErrorCode
import com.allcheck.identity.infrastructure.login.password.AdminPasswordHasher
import com.allcheck.identity.presentation.dto.response.session.TokenPairResponse
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional

@Service
class AdminLoginService(
    private val passwords: AdminPasswordHasher,
    private val sessions: AdminSessionService,
) {
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    fun login(loginId: String, password: String): TokenPairResponse {
        val candidate = sessions.candidate(loginId)
        if (!passwords.matches(password, candidate?.passwordHash)) throw BusinessException(IdentityErrorCode.INVALID_CREDENTIALS)
        return sessions.login(checkNotNull(candidate))
    }
}
