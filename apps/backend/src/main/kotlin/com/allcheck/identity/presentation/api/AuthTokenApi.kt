package com.allcheck.identity.presentation.api

import com.allcheck.global.exception.ErrorResponse
import com.allcheck.identity.presentation.dto.request.RefreshTokenRequest
import com.allcheck.identity.presentation.dto.response.TokenPairResponse
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import io.swagger.v3.oas.annotations.security.SecurityRequirements
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping

@Tag(name = "Authentication")
@RequestMapping("/api/v1/auth/tokens")
interface AuthTokenApi {
    @PostMapping("/refresh", produces = [MediaType.APPLICATION_JSON_VALUE])
    @Operation(summary = "Refresh Token을 교체하고 서비스 토큰 쌍 갱신", operationId = "refreshToken")
    @SecurityRequirements
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "갱신 성공. 세션 절대 만료는 연장하지 않음"),
        ApiResponse(responseCode = "400", description = "입력 형식 오류", content = [Content(schema = Schema(implementation = ErrorResponse::class))]),
        ApiResponse(responseCode = "401", description = "무효·만료·이미 사용한 Refresh Token", content = [Content(schema = Schema(implementation = ErrorResponse::class))]),
        ApiResponse(responseCode = "403", description = "비활성 계정", content = [Content(schema = Schema(implementation = ErrorResponse::class))]),
    )
    fun refresh(@Valid @RequestBody request: RefreshTokenRequest): ResponseEntity<TokenPairResponse>
}
