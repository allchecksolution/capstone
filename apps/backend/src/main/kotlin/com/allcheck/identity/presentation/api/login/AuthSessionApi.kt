package com.allcheck.identity.presentation.api.login

import com.allcheck.global.exception.ErrorResponse
import com.allcheck.identity.presentation.dto.request.login.AdminLoginRequest
import com.allcheck.identity.presentation.dto.request.login.KakaoLoginRequest
import com.allcheck.identity.presentation.dto.request.session.RefreshTokenRequest
import com.allcheck.identity.presentation.dto.response.session.TokenPairResponse
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import io.swagger.v3.oas.annotations.security.SecurityRequirements
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping

@Tag(name = "Authentication")
@RequestMapping("/api/v1/auth/sessions")
interface AuthSessionApi {
    @PostMapping("/admin")
    @Operation(summary = "사전 발급된 ID/PW로 관리자 로그인", operationId = "adminLogin")
    @SecurityRequirements
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "관리자 인증 성공. 현재 관리자 역할과 독립 세션의 토큰 쌍 반환"),
        ApiResponse(responseCode = "400", description = "입력 형식 오류", content = [Content(schema = Schema(implementation = ErrorResponse::class))]),
        ApiResponse(responseCode = "401", description = "아이디 또는 비밀번호 불일치", content = [Content(schema = Schema(implementation = ErrorResponse::class))]),
        ApiResponse(responseCode = "403", description = "유효한 자격 증명의 비활성 계정", content = [Content(schema = Schema(implementation = ErrorResponse::class))]),
    )
    fun adminLogin(@Valid @RequestBody request: AdminLoginRequest): ResponseEntity<TokenPairResponse>

    @PostMapping("/kakao")
    @Operation(summary = "카카오 토큰으로 기사 인증 계정에 로그인", operationId = "driverKakaoLogin")
    @SecurityRequirements
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "인증 성공. 기사 가입 상태는 driver API에서 별도 조회"),
        ApiResponse(responseCode = "400", description = "입력 형식 오류", content = [Content(schema = Schema(implementation = ErrorResponse::class))]),
        ApiResponse(responseCode = "401", description = "카카오 인증 실패", content = [Content(schema = Schema(implementation = ErrorResponse::class))]),
        ApiResponse(responseCode = "403", description = "비활성 계정", content = [Content(schema = Schema(implementation = ErrorResponse::class))]),
        ApiResponse(responseCode = "503", description = "카카오 인증 확인 불가", content = [Content(schema = Schema(implementation = ErrorResponse::class))]),
    )
    fun kakaoLogin(@Valid @RequestBody request: KakaoLoginRequest): ResponseEntity<TokenPairResponse>

    @PostMapping("/logout")
    @Operation(summary = "Refresh Token에 해당하는 세션 로그아웃", operationId = "logout")
    @SecurityRequirements
    @ApiResponses(
        ApiResponse(responseCode = "204", description = "로그아웃 완료. 무효·만료·폐기된 토큰도 다른 세션 변경 없이 동일 응답", content = [Content()]),
        ApiResponse(responseCode = "400", description = "입력 형식 오류", content = [Content(schema = Schema(implementation = ErrorResponse::class))]),
    )
    fun logout(@Valid @RequestBody request: RefreshTokenRequest): ResponseEntity<Void>
}
