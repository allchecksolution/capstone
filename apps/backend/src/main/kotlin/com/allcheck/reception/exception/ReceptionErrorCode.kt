package com.allcheck.reception.exception

import com.allcheck.global.exception.ErrorCode
import org.springframework.http.HttpStatus

enum class ReceptionErrorCode(
    override val status: HttpStatus,
    override val message: String,
) : ErrorCode {
    RECEPTION_IDENTITY_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "관리자 인증 연동이 준비되지 않았습니다."),
    RECORDING_STORAGE_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "녹음 저장소를 사용할 수 없습니다. 잠시 후 다시 시도해 주세요."),
    RECORDING_FORMAT_NOT_SUPPORTED(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "WAV 녹음 파일만 업로드할 수 있습니다."),
    RECEPTION_NOT_FOUND(HttpStatus.NOT_FOUND, "접수를 찾을 수 없습니다."),
    REVIEW_NOT_READY(HttpStatus.CONFLICT, "분석 처리가 끝난 뒤 검토를 완료해 주세요."),
    RECEPTION_ALREADY_CONFIRMED(HttpStatus.CONFLICT, "이미 검토가 완료된 접수입니다."),
    RECEPTION_NOT_CONFIRMED(HttpStatus.CONFLICT, "검토가 완료된 접수가 아닙니다."),
    RECEPTION_ALREADY_REGISTERED(HttpStatus.CONFLICT, "의뢰로 등록된 접수는 변경할 수 없습니다."),
    RECORDING_ALREADY_EXISTS(HttpStatus.CONFLICT, "이미 녹음이 예약된 접수입니다. 기존 파일로 업로드를 진행해 주세요."),
}
