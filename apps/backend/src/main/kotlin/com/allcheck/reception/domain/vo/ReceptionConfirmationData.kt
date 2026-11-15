package com.allcheck.reception.domain.vo

import java.time.OffsetDateTime

/**
 * 검토 완료에 제출할 전체 값. 필수 항목의 존재를 타입으로 구분하며 notes만 선택이다.
 * 빈 문자열·형식·방문 시각의 유효성 검증은 후속 검토 완료 동작에서 수행한다.
 */
data class ReceptionConfirmationData(
    val workType: WorkType,
    val symptoms: String,
    val address: String,
    val desiredVisitFrom: OffsetDateTime,
    val desiredVisitTo: OffsetDateTime,
    val contactPhone: String,
    val notes: String? = null,
)
