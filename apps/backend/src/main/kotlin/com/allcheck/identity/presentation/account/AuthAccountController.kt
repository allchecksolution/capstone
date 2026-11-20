package com.allcheck.identity.presentation.account

import com.allcheck.global.security.principal.AuthPrincipal
import com.allcheck.identity.presentation.api.account.AuthAccountApi
import com.allcheck.identity.presentation.dto.response.account.AuthUserResponse
import org.springframework.http.CacheControl
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.RestController

@RestController
class AuthAccountController : AuthAccountApi {
    override fun getMyAccount(principal: AuthPrincipal): ResponseEntity<AuthUserResponse> = ResponseEntity.ok()
        .cacheControl(CacheControl.noStore())
        .body(AuthUserResponse(principal.accountId, principal.userType, principal.role))
}
