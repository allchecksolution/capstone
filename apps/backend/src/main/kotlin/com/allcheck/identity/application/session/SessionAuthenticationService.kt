package com.allcheck.identity.application.session

import com.allcheck.identity.AuthenticatedAccount
import com.allcheck.identity.IdentityOperations
import com.allcheck.identity.infrastructure.repository.session.AuthSessionRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock

@Service
@Transactional(readOnly = true)
class SessionAuthenticationService(
    private val sessions: AuthSessionRepository,
    private val clock: Clock = Clock.systemUTC(),
) : IdentityOperations {
    override fun findAuthenticatedAccount(accountId: Long, sessionId: Long): AuthenticatedAccount? {
        val state = sessions.findAuthenticationState(accountId, sessionId) ?: return null
        if (!state.account.enabled || !state.session.isActiveAt(clock.instant())) return null
        return AuthenticatedAccount(checkNotNull(state.account.id), state.account.userType, state.account.adminCredential?.role)
    }
}
