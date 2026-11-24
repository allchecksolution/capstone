package com.allcheck.identity.presentation.session

import com.allcheck.identity.application.session.TokenRefreshService
import com.allcheck.identity.presentation.api.session.AuthTokenApi
import com.allcheck.identity.presentation.dto.request.session.RefreshTokenRequest
import com.allcheck.identity.presentation.dto.response.session.TokenPairResponse
import org.springframework.http.CacheControl
import org.springframework.http.HttpHeaders
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.RestController

@RestController
class AuthTokenController(
    private val sessions: TokenRefreshService,
) : AuthTokenApi {
    override fun refresh(request: RefreshTokenRequest): ResponseEntity<TokenPairResponse> = ResponseEntity.ok()
        .cacheControl(CacheControl.noStore())
        .header(HttpHeaders.PRAGMA, "no-cache")
        .body(sessions.refresh(request.refreshToken))
}
