package com.allcheck.identity.exception

import com.allcheck.global.exception.ErrorCode
import org.springframework.http.HttpStatus

enum class IdentityErrorCode(
    override val status: HttpStatus,
    override val message: String,
) : ErrorCode {
    KAKAO_AUTH_FAILED(HttpStatus.UNAUTHORIZED, "카카오 로그인에 실패했습니다."),
    ACCOUNT_DISABLED(HttpStatus.FORBIDDEN, "비활성화된 계정입니다."),
    ACCOUNT_NOT_FOUND(HttpStatus.NOT_FOUND, "인증 계정을 찾을 수 없습니다."),
    DRIVER_ACCOUNT_REQUIRED(HttpStatus.FORBIDDEN, "기사 인증 계정이 필요합니다."),
    INVALID_AUTH_SESSION(HttpStatus.UNAUTHORIZED, "유효하지 않은 인증 세션입니다."),
    PUSH_DEVICE_CONFLICT(HttpStatus.CONFLICT, "이미 다른 활성 기기에 연결된 푸시 정보입니다."),
    PUSH_DEVICE_FORBIDDEN(HttpStatus.FORBIDDEN, "다른 계정의 푸시 기기는 변경할 수 없습니다."),
    REFRESH_TOKEN_INVALID(HttpStatus.UNAUTHORIZED, "유효하지 않은 Refresh Token입니다."),
    KAKAO_SERVICE_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "카카오 인증을 확인할 수 없습니다. 잠시 후 다시 시도해 주세요."),
}
