package com.allcheck.global.exception

import com.fasterxml.jackson.annotation.JsonInclude

data class ErrorResponse(
    val code: String,
    val message: String,
    @get:JsonInclude(JsonInclude.Include.NON_NULL)
    val details: List<ValidationError>? = null,
) {
    companion object {
        fun from(errorCode: ErrorCode): ErrorResponse = ErrorResponse(
            code = errorCode.name,
            message = errorCode.message,
        )

        fun from(errorCode: ErrorCode, details: List<ValidationError>): ErrorResponse = ErrorResponse(
            code = errorCode.name,
            message = errorCode.message,
            details = details.toList(),
        )
    }
}
