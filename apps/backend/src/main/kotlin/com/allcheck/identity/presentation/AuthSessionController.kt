package com.allcheck.identity.presentation

import com.allcheck.identity.application.KakaoLoginService
import com.allcheck.identity.presentation.api.AuthSessionApi
import com.allcheck.identity.presentation.dto.request.KakaoLoginRequest
import com.allcheck.identity.presentation.dto.response.TokenPairResponse
import org.springframework.http.CacheControl
import org.springframework.http.HttpHeaders
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.RestController

@RestController
class AuthSessionController(
    private val loginService: KakaoLoginService,
) : AuthSessionApi {
    override fun kakaoLogin(request: KakaoLoginRequest): ResponseEntity<TokenPairResponse> = ResponseEntity.ok()
        .cacheControl(CacheControl.noStore())
        .header(HttpHeaders.PRAGMA, "no-cache")
        .body(loginService.login(request.kakaoAccessToken))
}
