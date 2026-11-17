package com.allcheck.reception.domain.vo.recording

/** 녹음 메타데이터의 업로드 상태. UPLOADED는 Backend의 객체 검증이 끝났음을 뜻한다. */
enum class RecordingUploadStatus {
    PENDING,
    UPLOADED,
}
