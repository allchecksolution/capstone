package com.allcheck.identity.exception

import com.allcheck.global.exception.ErrorCode
import org.springframework.http.HttpStatus

enum class IdentityErrorCode(
    override val status: HttpStatus,
    override val message: String,
) : ErrorCode {
    INVALID_CREDENTIALS(HttpStatus.UNAUTHORIZED, "아이디 또는 비밀번호를 확인해 주세요."),
    ADMIN_LOGIN_ID_ALREADY_EXISTS(HttpStatus.CONFLICT, "이미 사용 중인 관리자 아이디입니다."),
    BOOTSTRAP_ADMIN_ALREADY_EXISTS(HttpStatus.CONFLICT, "최초 관리자가 이미 지정되어 있습니다."),
    BOOTSTRAP_ADMIN_REQUIRED(HttpStatus.FORBIDDEN, "최초 관리자만 계정을 관리할 수 있습니다."),
    BOOTSTRAP_ADMIN_PROTECTED(HttpStatus.FORBIDDEN, "최초 관리자 계정은 변경 대상으로 지정하거나 탈퇴할 수 없습니다."),
    ADMIN_ACCOUNT_REQUIRED(HttpStatus.FORBIDDEN, "관리자 계정이 필요합니다."),
    ADMIN_ACCOUNT_VERSION_CONFLICT(HttpStatus.CONFLICT, "계정 정보가 변경되었습니다. 새로고침해 주세요."),
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
