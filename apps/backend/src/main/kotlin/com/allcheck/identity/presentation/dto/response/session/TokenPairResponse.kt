package com.allcheck.identity.presentation.dto.response.session

import com.allcheck.identity.presentation.dto.response.account.AuthUserResponse
import io.swagger.v3.oas.annotations.media.Schema

class TokenPairResponse(
    @field:Schema(description = "ALLCHECK 서비스 Access Token")
    val accessToken: String,
    @field:Schema(description = "ALLCHECK 서비스 Refresh Token")
    val refreshToken: String,
    @field:Schema(description = "Access Token 인증 방식")
    val tokenType: String = "Bearer",
    @field:Schema(description = "Access Token 유효 시간, 초")
    val expiresIn: Long,
    @field:Schema(description = "세션 절대 만료까지 남은 시간, 초")
    val refreshExpiresIn: Long,
    @field:Schema(description = "인증된 서비스 계정")
    val user: AuthUserResponse,
)
