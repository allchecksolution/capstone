package com.allcheck.identity.application

import com.allcheck.global.exception.BusinessException
import com.allcheck.global.security.jwt.JwtTokenProvider
import com.allcheck.identity.domain.entity.Account
import com.allcheck.identity.domain.entity.AuthSession
import com.allcheck.identity.domain.vo.SocialLogin
import com.allcheck.identity.domain.vo.SocialProvider
import com.allcheck.identity.exception.IdentityErrorCode
import com.allcheck.identity.infrastructure.repository.AccountRepository
import com.allcheck.identity.infrastructure.repository.AuthSessionRepository
import com.allcheck.identity.infrastructure.token.RefreshTokenProvider
import com.allcheck.identity.infrastructure.token.SessionProperties
import com.allcheck.identity.presentation.dto.response.AuthUserResponse
import com.allcheck.identity.presentation.dto.response.TokenPairResponse
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock

@Service
@Transactional(readOnly = true)
class KakaoSessionService(
    private val accounts: AccountRepository,
    private val sessions: AuthSessionRepository,
    private val refreshTokens: RefreshTokenProvider,
    private val accessTokens: JwtTokenProvider,
    private val properties: SessionProperties,
    private val clock: Clock = Clock.systemUTC(),
) {
    @Transactional
    fun login(socialLogin: SocialLogin): TokenPairResponse {
        require(socialLogin.provider == SocialProvider.KAKAO) { "Kakao login requires a Kakao identity." }
        val candidate = Account.driver(socialLogin, clock.instant())
        accounts.insertKakaoIfAbsent(candidate.id, socialLogin.providerUserId, candidate.createdAt)
        val account = checkNotNull(accounts.findBySocialLoginForUpdate(socialLogin)) { "Kakao account was not resolved." }
        if (!account.enabled) throw BusinessException(IdentityErrorCode.ACCOUNT_DISABLED)

        val issuedAt = clock.instant()
        val refreshToken = refreshTokens.generate()
        val session = AuthSession(account.id, refreshTokens.hash(refreshToken), issuedAt, issuedAt.plus(properties.expiration))
        sessions.saveAndFlush(session)
        val accessToken = accessTokens.createAccessToken(account.id, session.id)
        return TokenPairResponse(
            accessToken = accessToken,
            refreshToken = refreshToken,
            expiresIn = accessTokens.lifetimeSeconds,
            refreshExpiresIn = properties.expiration.seconds,
            user = AuthUserResponse(account.id, account.userType, account.adminCredential?.role),
        )
    }
}
