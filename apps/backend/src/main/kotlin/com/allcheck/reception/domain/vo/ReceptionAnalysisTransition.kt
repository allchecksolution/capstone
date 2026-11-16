package com.allcheck.reception.domain.vo

/** 단계 진행 결과. FAILED는 실패/미반영 종료이며 알림 정책은 애플리케이션에서 확인한다. IGNORED는 변경이 없다. */
enum class ReceptionAnalysisTransition {
    ADVANCED,
    FAILED,
    IGNORED,
}
