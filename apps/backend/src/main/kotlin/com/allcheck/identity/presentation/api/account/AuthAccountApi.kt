package com.allcheck.identity.presentation.api.account

import com.allcheck.global.exception.ErrorResponse
import com.allcheck.global.security.principal.AuthPrincipal
import com.allcheck.identity.presentation.dto.response.account.AuthUserResponse
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import io.swagger.v3.oas.annotations.security.SecurityRequirement
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping

@Tag(name = "Authentication")
@RequestMapping("/api/v1/auth/accounts")
interface AuthAccountApi {
    @GetMapping("/me", produces = [MediaType.APPLICATION_JSON_VALUE])
    @Operation(summary = "내 인증 계정 조회", operationId = "getMyAuthAccount")
    @SecurityRequirement(name = "bearerAuth")
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "현재 인증 계정. 기사 개인정보와 가입 상태는 포함하지 않음"),
        ApiResponse(responseCode = "401", description = "인증 정보 누락 또는 사용할 수 없는 토큰·계정·세션", content = [Content(schema = Schema(implementation = ErrorResponse::class))]),
    )
    fun getMyAccount(@Parameter(hidden = true) @AuthenticationPrincipal principal: AuthPrincipal): ResponseEntity<AuthUserResponse>
}
