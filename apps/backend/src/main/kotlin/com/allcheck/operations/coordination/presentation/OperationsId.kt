package com.allcheck.operations.coordination.presentation

import com.allcheck.global.exception.BusinessException
import com.allcheck.global.exception.GlobalErrorCode

enum class OperationsId(private val prefix: String) {
    RECEPTION("rec"),
    REQUEST("req"),
    ROUND("round"),
    OFFER("offer"),
    WORK("work"),
    CANCELLATION("cancel"),
    REPORT("report"),
    PHOTO("photo"),
    DECISION("decision"),
    ;

    fun format(id: Long) = "${prefix}_$id"
    fun parse(value: String): Long {
        val number = value.removePrefix("${prefix}_")
        return number.toLongOrNull()?.takeIf { value.startsWith("${prefix}_") && it > 0 && number.all { digit -> digit in '0'..'9' } }
            ?: throw BusinessException(GlobalErrorCode.INVALID_INPUT_VALUE)
    }
}
