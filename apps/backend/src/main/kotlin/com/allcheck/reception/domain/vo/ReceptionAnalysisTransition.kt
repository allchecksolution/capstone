package com.allcheck.reception.domain.vo

/** 단계 진행 결과. FAILED는 실패 상태 저장·알림 대상이며, IGNORED는 상태를 변경하지 않는다. */
enum class ReceptionAnalysisTransition {
    ADVANCED,
    FAILED,
    IGNORED,
}
