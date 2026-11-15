package com.allcheck.reception.domain.vo

import java.time.OffsetDateTime

/** AI 추출 원본 값. 누락을 허용하며 현재 AI 계약에 없는 연락처는 포함하지 않는다. */
data class ReceptionAiOriginal(
    val workType: WorkType? = null,
    val symptoms: String? = null,
    val address: String? = null,
    val desiredVisitFrom: OffsetDateTime? = null,
    val desiredVisitTo: OffsetDateTime? = null,
    val notes: String? = null,
)
