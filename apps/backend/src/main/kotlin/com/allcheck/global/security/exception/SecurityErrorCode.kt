package com.allcheck.global.security.exception

import com.allcheck.global.exception.ErrorCode
import org.springframework.http.HttpStatus

enum class SecurityErrorCode(
    override val status: HttpStatus,
    override val message: String,
) : ErrorCode {
    UNAUTHENTICATED(HttpStatus.UNAUTHORIZED, "인증이 필요합니다."),
    TOKEN_EXPIRED(HttpStatus.UNAUTHORIZED, "토큰이 만료되었습니다."),
    INVALID_TOKEN(HttpStatus.UNAUTHORIZED, "유효하지 않은 토큰입니다."),
    ACCESS_DENIED(HttpStatus.FORBIDDEN, "접근 권한이 없습니다."),
}
