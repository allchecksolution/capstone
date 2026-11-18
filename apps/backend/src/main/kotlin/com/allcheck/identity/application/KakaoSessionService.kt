package com.allcheck.identity.application

import com.allcheck.identity.domain.entity.Account
import com.allcheck.identity.domain.vo.SocialLogin
import com.allcheck.identity.domain.vo.SocialProvider
import com.allcheck.identity.infrastructure.repository.AccountRepository
import com.allcheck.identity.presentation.dto.response.TokenPairResponse
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
        val candidate = Account.driver(socialLogin, clock.instant())
        accounts.insertKakaoIfAbsent(candidate.id, socialLogin.providerUserId, candidate.createdAt)
        val account = checkNotNull(accounts.findBySocialLoginForUpdate(socialLogin)) { "Kakao account was not resolved." }
        return issuance.issue(account)
    }
}
