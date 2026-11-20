package com.allcheck.reception.domain.vo.review

import com.allcheck.global.exception.BusinessException
import com.allcheck.global.exception.GlobalErrorCode
import java.time.Instant
import java.time.OffsetDateTime

/**
 * 검토 완료에 제출할 전체 값. 필수 항목의 존재를 타입으로 구분하며 notes만 선택이다.
 * 현재 시각을 사용하는 검증은 객체 생성·과거 자료 조회가 아닌 검토 완료 명령에서 수행한다.
 */
data class ReceptionConfirmationData(
    val workType: WorkType,
    val symptoms: String,
    val address: String,
    val desiredVisitFrom: OffsetDateTime,
    val desiredVisitTo: OffsetDateTime,
    val contactPhone: String,
    val notes: String? = null,
) {
    fun validateForConfirmation(now: Instant) {
        if (!hasValidText() || !hasValidVisitWindow(now)) {
            throw BusinessException(GlobalErrorCode.INVALID_INPUT_VALUE)
        }
    }

    private fun hasValidText(): Boolean = symptoms.isNotBlank() &&
        symptoms.codePointCount(0, symptoms.length) <= MAX_SYMPTOMS_LENGTH &&
        address.isNotBlank() &&
        address.codePointCount(0, address.length) <= MAX_ADDRESS_LENGTH &&
        CONTACT_PHONE_PATTERN.matches(contactPhone) &&
        (notes == null || notes.codePointCount(0, notes.length) <= MAX_NOTES_LENGTH)

    private fun hasValidVisitWindow(now: Instant): Boolean = desiredVisitTo.isAfter(desiredVisitFrom) &&
        desiredVisitTo.toInstant().isAfter(now)

    private companion object {
        const val MAX_SYMPTOMS_LENGTH = 2000
        const val MAX_ADDRESS_LENGTH = 300
        const val MAX_NOTES_LENGTH = 2000
        val CONTACT_PHONE_PATTERN = Regex("[0-9+-]{9,20}")
    }
}
