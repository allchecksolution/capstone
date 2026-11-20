package com.allcheck.identity.presentation.api.session

import com.allcheck.global.exception.ErrorResponse
import com.allcheck.identity.presentation.dto.request.session.RefreshTokenRequest
import com.allcheck.identity.presentation.dto.response.session.TokenPairResponse
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.headers.Header
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
    @Operation(summary = "Refresh Token을 교체하고 서비스 토큰 쌍 갱신", description = "관리자·기사 계정별로 모든 기기를 합산하여 첫 요청부터 60초 동안 최대 30회. 초과 시 남은 시간 후 자동 해제.", operationId = "refreshToken")
    @SecurityRequirements
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "갱신 성공. 세션 절대 만료는 연장하지 않음"),
        ApiResponse(responseCode = "400", description = "입력 형식 오류", content = [Content(schema = Schema(implementation = ErrorResponse::class))]),
        ApiResponse(responseCode = "401", description = "무효·만료·이미 사용한 Refresh Token", content = [Content(schema = Schema(implementation = ErrorResponse::class))]),
        ApiResponse(responseCode = "403", description = "비활성 계정", content = [Content(schema = Schema(implementation = ErrorResponse::class))]),
        ApiResponse(
            responseCode = "429",
            description = "계정별 갱신 요청 한도 초과. 토큰 회전이나 세션 폐기는 수행하지 않음",
            headers = [Header(name = "Retry-After", description = "다시 요청하기까지 남은 초", schema = Schema(type = "integer", format = "int64", minimum = "1"))],
            content = [Content(schema = Schema(implementation = ErrorResponse::class))],
        ),
    )
    fun refresh(@Valid @RequestBody request: RefreshTokenRequest): ResponseEntity<TokenPairResponse>
}
