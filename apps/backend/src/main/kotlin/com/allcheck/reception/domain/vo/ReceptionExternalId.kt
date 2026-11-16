package com.allcheck.reception.domain.vo

import com.allcheck.global.exception.BusinessException
import com.allcheck.global.exception.GlobalErrorCode

/** API·AI 계약의 문자열과 내부 숫자 식별자를 변환한다. 접두어별 자원 유형을 검증한다. */
enum class ReceptionExternalId(private val prefix: String) {
    RECEPTION("rec_"),
    RECORDING("file_rec_"),
    ANALYSIS("anl_"),
    TRANSCRIPT("file_stt_"),
    ;

    fun format(id: Long): String {
        require(id > 0)
        return prefix + id
    }

    fun parse(value: String): Long {
        val number = value.removePrefix(prefix)
        val id = number.toLongOrNull()
        if (!value.startsWith(prefix) || id == null || id <= 0 || number.any { it !in '0'..'9' }) {
            throw BusinessException(GlobalErrorCode.INVALID_INPUT_VALUE)
        }
        return id
    }
}
