package com.allcheck.global.exception

data class ValidationError(
    val field: String,
    val reason: String,
)
