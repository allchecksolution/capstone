package com.allcheck.identity.application.session

import com.allcheck.global.exception.BusinessException
import com.allcheck.global.security.jwt.JwtTokenProvider
import com.allcheck.identity.domain.entity.account.Account
import com.allcheck.identity.domain.entity.session.AuthSession
import com.allcheck.identity.exception.IdentityErrorCode
import com.allcheck.identity.infrastructure.repository.session.AuthSessionRepository
import com.allcheck.identity.infrastructure.session.RefreshTokenProvider
import com.allcheck.identity.infrastructure.session.SessionProperties
import com.allcheck.identity.presentation.dto.response.account.AuthUserResponse
import com.allcheck.identity.presentation.dto.response.session.TokenPairResponse
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.time.Clock

@Service
@Transactional(readOnly = true)
class SessionIssuanceService(
    private val sessions: AuthSessionRepository,
    private val refreshTokens: RefreshTokenProvider,
    private val accessTokens: JwtTokenProvider,
    private val properties: SessionProperties,
    private val clock: Clock = Clock.systemUTC(),
) {
    /** 호출자는 같은 쓰기 트랜잭션에서 현재 Account를 잠근 뒤 호출한다. */
    @Transactional(propagation = Propagation.MANDATORY)
    fun issue(account: Account): TokenPairResponse {
        if (!account.enabled) throw BusinessException(IdentityErrorCode.ACCOUNT_DISABLED)
        val issuedAt = clock.instant()
        val refreshToken = refreshTokens.generate()
        val session = AuthSession(checkNotNull(account.id), refreshTokens.hash(refreshToken), issuedAt, issuedAt.plus(properties.expiration))
        sessions.saveAndFlush(session)
        val accessToken = accessTokens.createAccessToken(checkNotNull(account.id), checkNotNull(session.id))
        return TokenPairResponse(
            accessToken = accessToken,
            refreshToken = refreshToken,
            expiresIn = accessTokens.lifetimeSeconds,
            refreshExpiresIn = properties.expiration.seconds,
            user = AuthUserResponse(checkNotNull(account.id), account.userType, account.adminCredential?.role),
        )
    }
}
