package com.allcheck.operations.work.exception

import com.allcheck.global.exception.ErrorCode
import org.springframework.http.HttpStatus

enum class WorkErrorCode(override val status: HttpStatus, override val message: String) : ErrorCode {
    DRIVER_OCCUPIED(HttpStatus.CONFLICT, "기사가 이미 미완료 작업을 수행 중입니다."),
    REQUEST_ALREADY_ASSIGNED(HttpStatus.CONFLICT, "이미 작업이 배정된 의뢰입니다."),
}
