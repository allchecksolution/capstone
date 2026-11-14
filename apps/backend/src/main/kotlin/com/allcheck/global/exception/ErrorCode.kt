package com.allcheck.global.exception

import org.springframework.http.HttpStatus

interface ErrorCode {
    val status: HttpStatus
    val name: String
    val message: String
}
