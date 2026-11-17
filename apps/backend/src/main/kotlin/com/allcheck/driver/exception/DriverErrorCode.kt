package com.allcheck.driver.exception

import com.allcheck.global.exception.ErrorCode
import org.springframework.http.HttpStatus

enum class DriverErrorCode(
    override val status: HttpStatus,
    override val message: String,
) : ErrorCode {
    BUSINESS_INFO_INVALID(HttpStatus.BAD_REQUEST, "사업자 정보를 확인해 주세요."),
    DRIVER_NOT_FOUND(HttpStatus.NOT_FOUND, "기사 가입 신청을 찾을 수 없습니다."),
    DRIVER_ALREADY_REGISTERED(HttpStatus.CONFLICT, "이미 가입 신청한 계정입니다."),
    DRIVER_ALREADY_DECIDED(HttpStatus.CONFLICT, "이미 처리된 신청입니다."),
    NOT_REJECTED(HttpStatus.CONFLICT, "반려 상태에서만 재신청할 수 있습니다."),
    APPROVAL_PRECONDITION_FAILED(HttpStatus.CONFLICT, "승인 조건을 충족하지 않습니다."),
    DRIVER_NOT_ACTIVE(HttpStatus.FORBIDDEN, "활성 기사만 이용할 수 있습니다."),
    VERSION_CONFLICT(HttpStatus.CONFLICT, "최신 버전과 상태를 다시 조회해 주세요."),
    IDEMPOTENCY_KEY_REUSED(HttpStatus.CONFLICT, "같은 멱등성 키에 다른 요청을 사용할 수 없습니다."),
    OPERATION_IN_PROGRESS(HttpStatus.CONFLICT, "같은 요청을 처리 중입니다."),
    WITHDRAWAL_BLOCKED_ACTIVE_WORK(HttpStatus.CONFLICT, "진행 중인 작업이 있어 탈퇴할 수 없습니다."),
    BUSINESS_VERIFICATION_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "현재 사업자 검증을 할 수 없습니다. 잠시 후 다시 시도해 주세요."),
}
