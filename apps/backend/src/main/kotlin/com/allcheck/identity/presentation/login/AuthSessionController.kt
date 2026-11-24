package com.allcheck.identity.presentation.login

import com.allcheck.identity.application.login.AdminLoginService
import com.allcheck.identity.application.login.KakaoLoginService
import com.allcheck.identity.application.session.SessionLifecycleService
import com.allcheck.identity.presentation.api.login.AuthSessionApi
import com.allcheck.identity.presentation.dto.request.login.AdminLoginRequest
import com.allcheck.identity.presentation.dto.request.login.KakaoLoginRequest
import com.allcheck.identity.presentation.dto.request.session.RefreshTokenRequest
import com.allcheck.identity.presentation.dto.response.session.TokenPairResponse
import org.springframework.http.CacheControl
import org.springframework.http.HttpHeaders
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.RestController

@RestController
class AuthSessionController(
    private val adminLoginService: AdminLoginService,
    private val loginService: KakaoLoginService,
    private val sessions: SessionLifecycleService,
) : AuthSessionApi {
    override fun adminLogin(request: AdminLoginRequest): ResponseEntity<TokenPairResponse> = ResponseEntity.ok()
        .cacheControl(CacheControl.noStore())
        .header(HttpHeaders.PRAGMA, "no-cache")
        .body(adminLoginService.login(request.loginId, request.password))

    override fun kakaoLogin(request: KakaoLoginRequest): ResponseEntity<TokenPairResponse> = ResponseEntity.ok()
        .cacheControl(CacheControl.noStore())
        .header(HttpHeaders.PRAGMA, "no-cache")
        .body(loginService.login(request.kakaoAccessToken))

    override fun logout(request: RefreshTokenRequest): ResponseEntity<Void> {
        sessions.logout(request.refreshToken)
        return ResponseEntity.noContent()
            .cacheControl(CacheControl.noStore())
            .header(HttpHeaders.PRAGMA, "no-cache")
            .build()
    }
}
