package com.allcheck.reception.domain.vo.analysis

/** 현재 분석 단계. 동기 STT 호출 중 AI 내부의 다운로드 진행률은 추정하지 않는다. */
enum class ReceptionAnalysisStage {
    QUEUED,
    RECORDING_DOWNLOAD,
    STT,
    TRANSCRIPT_STORAGE,
    STRUCTURE,
    RESULT_APPLICATION,
    COMPLETED,
}
