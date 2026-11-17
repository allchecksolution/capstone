package com.allcheck.identity.application

import com.allcheck.global.exception.BusinessException
import com.allcheck.global.security.jwt.JwtTokenProvider
import com.allcheck.identity.exception.IdentityErrorCode
import com.allcheck.identity.infrastructure.repository.AccountRepository
import com.allcheck.identity.infrastructure.repository.AuthSessionRepository
import com.allcheck.identity.infrastructure.token.RefreshTokenProvider
import com.allcheck.identity.presentation.dto.response.AuthUserResponse
import com.allcheck.identity.presentation.dto.response.TokenPairResponse
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.Duration

@Service
@Transactional(readOnly = true)
class SessionLifecycleService(
    private val accounts: AccountRepository,
    private val sessions: AuthSessionRepository,
    private val refreshTokens: RefreshTokenProvider,
    private val accessTokens: JwtTokenProvider,
    private val clock: Clock = Clock.systemUTC(),
) {
    @Transactional
    fun refresh(refreshToken: String): TokenPairResponse {
        val expectedHash = refreshTokens.hash(refreshToken)
        // 식별자만 먼저 조회한다. 잠금을 기다리는 동안 바뀔 수 있는 세션은 미리 로드하지 않는다.
        val accountId = sessions.findAccountIdByRefreshTokenHash(expectedHash)
            ?: throw BusinessException(IdentityErrorCode.REFRESH_TOKEN_INVALID)
        val account = accounts.findByIdForUpdate(accountId)
            ?: throw BusinessException(IdentityErrorCode.REFRESH_TOKEN_INVALID)
        val session = sessions.findByRefreshTokenHashForUpdate(expectedHash)
            ?: throw BusinessException(IdentityErrorCode.REFRESH_TOKEN_INVALID)
        val now = clock.instant()
        if (!session.isActiveAt(now)) throw BusinessException(IdentityErrorCode.REFRESH_TOKEN_INVALID)
        if (!account.enabled) throw BusinessException(IdentityErrorCode.ACCOUNT_DISABLED)

        val replacement = refreshTokens.generate()
        check(session.rotateRefreshToken(expectedHash, refreshTokens.hash(replacement), now))
        sessions.flush()
        // 서명 실패를 포함해 트랜잭션이 실패하면 기존 Refresh Token도 그대로 유지한다.
        val accessToken = accessTokens.createAccessToken(account.id, session.id)
        return TokenPairResponse(
            accessToken = accessToken,
            refreshToken = replacement,
            expiresIn = accessTokens.lifetimeSeconds,
            refreshExpiresIn = Duration.between(clock.instant(), session.expiresAt).seconds.coerceAtLeast(0),
            user = AuthUserResponse(account.id, account.userType, account.adminCredential?.role),
        )
    }

    @Transactional
    fun logout(refreshToken: String) {
        val hash = refreshTokens.hash(refreshToken)
        val accountId = sessions.findAccountIdByRefreshTokenHash(hash) ?: return
        accounts.findByIdForUpdate(accountId) ?: return
        val session = sessions.findByRefreshTokenHashForUpdate(hash) ?: return
        val now = clock.instant()
        if (!session.isActiveAt(now)) return
        session.revoke(now)
    }
}
