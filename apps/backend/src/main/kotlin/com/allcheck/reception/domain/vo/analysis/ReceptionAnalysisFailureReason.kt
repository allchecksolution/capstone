package com.allcheck.reception.domain.vo.analysis

/** 분석 조회에 보관하는 처리 실패 사유. HTTP 요청을 거부하는 BusinessException과 구분한다. */
enum class ReceptionAnalysisFailureReason(val message: String) {
    AI_STT_FAILED("녹음 전사 처리에 실패했습니다. 접수 정보를 직접 입력해 주세요."),
    STT_NO_CONTENT("전사 내용이 없습니다. 녹음 원본을 확인하고 접수 정보를 직접 입력해 주세요."),
    TRANSCRIPT_STORAGE_FAILED("STT 원문을 보관하지 못했습니다. 녹음 원본을 확인하고 접수 정보를 직접 입력해 주세요."),
    ANALYSIS_TIMEOUT("분석 작업의 처리 기한이 지났습니다. 접수 정보를 직접 입력해 주세요."),
    AI_STRUCTURING_FAILED("상담 내용의 구조화 분석에 실패했습니다. 접수 정보를 직접 입력해 주세요."),
    AI_TIMEOUT("AI 서버 응답을 받지 못했습니다. 원격 완료 여부는 알 수 없습니다."),
    STALE_ANALYSIS_RESULT("접수가 변경되어 결과를 반영하지 않았습니다."),
}
