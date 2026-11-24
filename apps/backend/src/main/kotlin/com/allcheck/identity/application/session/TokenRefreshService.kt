package com.allcheck.identity.application.session

import com.allcheck.global.ratelimit.RequestLimitPolicy
import com.allcheck.global.ratelimit.RequestLimitService
import com.allcheck.identity.presentation.dto.response.session.TokenPairResponse
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.time.Duration

@Service
class TokenRefreshService(
    private val sessions: SessionLifecycleService,
    private val limits: RequestLimitService,
) {
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    fun refresh(refreshToken: String): TokenPairResponse {
        val accountId = sessions.identifyRefreshAccount(refreshToken)
        limits.check(accountId.toString(), REFRESH_LIMIT)
        // 식별 이후 회전·로그아웃·비활성화가 발생할 수 있으므로 잠금 후 다시 검증한다.
        return sessions.refresh(refreshToken)
    }

    companion object {
        private val REFRESH_LIMIT = RequestLimitPolicy("token-refresh", 30, Duration.ofMinutes(1))
    }
}
