package com.allcheck.operations.coordination.presentation.dto.request

import com.allcheck.global.exception.BusinessException
import com.allcheck.global.exception.GlobalErrorCode
import com.fasterxml.jackson.annotation.JsonAnySetter
import tools.jackson.databind.JsonNode

abstract class StrictOperationsRequest {
    @JsonAnySetter
    @Suppress("UNUSED_PARAMETER")
    fun rejectUnknown(field: String, value: JsonNode?): Unit = throw BusinessException(GlobalErrorCode.INVALID_INPUT_VALUE)
}
