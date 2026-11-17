package com.allcheck.global.security.exception

import org.springframework.security.core.AuthenticationException

class TokenAuthenticationException : AuthenticationException {
    val errorCode: SecurityErrorCode

    constructor(errorCode: SecurityErrorCode) : super(errorCode.message) {
        this.errorCode = errorCode
    }

    constructor(errorCode: SecurityErrorCode, cause: Throwable) : super(errorCode.message, cause) {
        this.errorCode = errorCode
    }
}
