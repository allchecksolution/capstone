package com.allcheck.global.exception

import org.springframework.http.HttpStatus

enum class GlobalErrorCode(
    override val status: HttpStatus,
    override val message: String,
) : ErrorCode {
    INVALID_INPUT_VALUE(HttpStatus.BAD_REQUEST, "입력값이 올바르지 않습니다."),
    VERSION_CONFLICT(HttpStatus.CONFLICT, "최신 버전과 상태를 다시 조회해 주세요."),
    IDEMPOTENCY_KEY_REUSED(HttpStatus.CONFLICT, "같은 멱등성 키로 다른 요청을 보낼 수 없습니다."),
    OPERATION_IN_PROGRESS(HttpStatus.CONFLICT, "같은 요청이 처리 중입니다. 잠시 후 다시 시도해 주세요."),
    FILE_ALREADY_UPLOADED(HttpStatus.CONFLICT, "이미 업로드가 완료된 파일입니다."),
    FILE_UPLOAD_NOT_VERIFIED(HttpStatus.BAD_REQUEST, "업로드된 파일이 예약한 정보와 일치하지 않습니다."),
    METHOD_NOT_ALLOWED(HttpStatus.METHOD_NOT_ALLOWED, "지원하지 않는 HTTP 메서드입니다."),
    INTERNAL_SERVER_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "서버 내부 오류가 발생했습니다."),
}
