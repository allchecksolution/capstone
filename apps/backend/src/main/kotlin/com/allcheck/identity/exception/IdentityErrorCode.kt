package com.allcheck.identity.exception

import com.allcheck.global.exception.ErrorCode
import org.springframework.http.HttpStatus

enum class IdentityErrorCode(
    override val status: HttpStatus,
    override val message: String,
) : ErrorCode {
    KAKAO_AUTH_FAILED(HttpStatus.UNAUTHORIZED, "카카오 로그인에 실패했습니다."),
    KAKAO_SERVICE_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "카카오 인증을 확인할 수 없습니다. 잠시 후 다시 시도해 주세요."),
}
