package com.allcheck.identity.application

import com.allcheck.identity.infrastructure.kakao.KakaoClient
import com.allcheck.identity.presentation.dto.response.TokenPairResponse
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional

@Service
class KakaoLoginService(
    private val kakaoClient: KakaoClient,
    private val sessionService: KakaoSessionService,
) {
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    fun login(kakaoAccessToken: String): TokenPairResponse {
        val socialLogin = kakaoClient.verify(kakaoAccessToken)
        return sessionService.login(socialLogin)
    }
}
