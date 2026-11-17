package com.allcheck.reception.domain.vo.analysis

/** 식별자 검증을 마친 구조화 응답의 내용. 원본과 누락 목록을 보완하거나 재계산하지 않는다. */
class ReceptionStructuredResult(
    val aiOriginal: ReceptionAiOriginal,
    missingFields: List<String>,
) {
    private val storedMissingFields = missingFields.toList()

    val missingFields: List<String>
        get() = storedMissingFields.toList()

    init {
        require(aiOriginal.symptoms == null || aiOriginal.symptoms.codePointCount(0, aiOriginal.symptoms.length) in 1..2000)
        require(aiOriginal.address == null || aiOriginal.address.codePointCount(0, aiOriginal.address.length) in 1..300)
        require(aiOriginal.notes == null || aiOriginal.notes.codePointCount(0, aiOriginal.notes.length) <= 2000)
        require(storedMissingFields.all { it in ALLOWED_MISSING_FIELDS })
        require(storedMissingFields.distinct().size == storedMissingFields.size)
    }

    private companion object {
        val ALLOWED_MISSING_FIELDS = setOf("workType", "symptoms", "address", "desiredVisitFrom", "desiredVisitTo")
    }
}
