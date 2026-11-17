package com.allcheck.reception.domain.vo

/** STT 실행·원문 DB 보관·구조화 각각의 진행 상태. 분석 시도 전체 상태와 구분한다. */
enum class ReceptionProcessingStatus {
    PENDING,
    PROCESSING,
    SUCCEEDED,
    FAILED,
    NOT_AVAILABLE,
}
