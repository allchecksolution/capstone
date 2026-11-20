package com.allcheck.driver.presentation.dto.request

import com.fasterxml.jackson.annotation.JsonAnySetter

abstract class StrictDriverRequest {
    @JsonAnySetter
    @Suppress("UNUSED_PARAMETER")
    fun rejectUnknownField(field: String, value: Any?): Nothing = throw IllegalArgumentException("Unknown driver request fields are not supported.")
}
