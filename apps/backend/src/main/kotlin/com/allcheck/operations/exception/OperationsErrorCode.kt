package com.allcheck.operations.exception

import com.allcheck.global.exception.ErrorCode
import org.springframework.http.HttpStatus

enum class OperationsErrorCode(override val status: HttpStatus, override val message: String) : ErrorCode {
    OPERATIONS_NOT_FOUND(HttpStatus.NOT_FOUND, "업무 자원을 찾을 수 없습니다."),
    OPERATIONS_STATE_CONFLICT(HttpStatus.CONFLICT, "현재 업무 상태에서 수행할 수 없습니다."),
    DRIVER_NOT_ELIGIBLE(HttpStatus.CONFLICT, "현재 배차를 수락할 수 없는 기사입니다."),
    OFFER_EXPIRED(HttpStatus.CONFLICT, "배차 응답 시간이 만료되었습니다."),
    OPERATIONS_INTEGRATION_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "필요한 외부 도메인 연동이 준비되지 않았습니다."),
    SITE_NOT_RESOLVED(HttpStatus.UNPROCESSABLE_ENTITY, "주소를 정확한 현장 위치로 확인할 수 없습니다."),
    GEOCODER_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "현장 좌표 제공자를 사용할 수 없습니다."),
    PHOTO_STORAGE_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "작업 사진 저장소를 사용할 수 없습니다."),
}
