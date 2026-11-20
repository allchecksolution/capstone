package com.allcheck.identity.presentation.dto.response.account

import com.allcheck.identity.AdminRole
import com.allcheck.identity.UserType
import io.swagger.v3.oas.annotations.media.Schema

data class AuthUserResponse(
    @field:Schema(description = "identity 내부 계정 식별자. 카카오 회원번호 및 driverId와 구분")
    val accountId: Long,
    @field:Schema(description = "서버에서 확인한 계정 유형")
    val userType: UserType,
    @field:Schema(description = "관리자 역할. 기사 계정은 null")
    val role: AdminRole?,
)
