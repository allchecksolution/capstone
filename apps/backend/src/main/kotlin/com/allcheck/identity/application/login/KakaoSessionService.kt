package com.allcheck.identity.application.login

import com.allcheck.identity.application.session.SessionIssuanceService
import com.allcheck.identity.domain.vo.account.SocialLogin
import com.allcheck.identity.domain.vo.account.SocialProvider
import com.allcheck.identity.infrastructure.repository.account.AccountRepository
import com.allcheck.identity.presentation.dto.response.session.TokenPairResponse
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock

@Service
@Transactional(readOnly = true)
class KakaoSessionService(
    private val accounts: AccountRepository,
    private val issuance: SessionIssuanceService,
    private val clock: Clock = Clock.systemUTC(),
) {
    @Transactional
    fun login(socialLogin: SocialLogin): TokenPairResponse {
        require(socialLogin.provider == SocialProvider.KAKAO) { "Kakao login requires a Kakao identity." }
        accounts.insertKakaoIfAbsent(socialLogin.providerUserId, clock.instant())
        val account = checkNotNull(accounts.findBySocialLoginForUpdate(socialLogin)) { "Kakao account was not resolved." }
        return issuance.issue(account)
    }
}
