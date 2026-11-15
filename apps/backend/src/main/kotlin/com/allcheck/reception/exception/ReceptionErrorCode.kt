package com.allcheck.reception.exception

import com.allcheck.global.exception.ErrorCode
import org.springframework.http.HttpStatus

enum class ReceptionErrorCode(
    override val status: HttpStatus,
    override val message: String,
) : ErrorCode {
    REVIEW_NOT_READY(HttpStatus.CONFLICT, "분석 처리가 끝난 뒤 검토를 완료해 주세요."),
    RECEPTION_ALREADY_CONFIRMED(HttpStatus.CONFLICT, "이미 검토가 완료된 접수입니다."),
    RECEPTION_ALREADY_REGISTERED(HttpStatus.CONFLICT, "의뢰로 등록된 접수는 변경할 수 없습니다."),
}
