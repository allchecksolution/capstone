package com.allcheck.reception.domain.vo

import java.time.OffsetDateTime

/**
 * 접수에 저장된 최신 편집값. 최초 AI 결과에서 빠진 값은 null로 표현한다.
 * 검토 완료 시에는 필수값을 모두 검증한 뒤 전체 값을 교체하며, 관리자 임시 저장은 제공하지 않는다.
 */
data class ReceptionData(
    val workType: WorkType? = null,
    val symptoms: String? = null,
    val address: String? = null,
    val desiredVisitFrom: OffsetDateTime? = null,
    val desiredVisitTo: OffsetDateTime? = null,
    val contactPhone: String? = null,
    val notes: String? = null,
)
