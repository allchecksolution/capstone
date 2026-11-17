package com.allcheck.reception.domain.vo.analysis

/** 분석 시도 전체의 상태. STT·원문 저장·구조화 개별 단계의 처리 상태와 구분한다. */
enum class ReceptionAnalysisStatus {
    PENDING,
    PROCESSING,
    SUCCEEDED,
    FAILED,
    DISCARDED,
}
