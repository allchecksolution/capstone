package com.allcheck.global.security.handler

import com.allcheck.global.exception.ErrorResponse
import com.allcheck.global.security.exception.SecurityErrorCode
import com.allcheck.global.security.exception.TokenAuthenticationException
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.security.core.AuthenticationException
import org.springframework.security.web.AuthenticationEntryPoint
import org.springframework.stereotype.Component
import tools.jackson.databind.json.JsonMapper

@Component
class CustomAuthenticationEntryPoint(
    private val jsonMapper: JsonMapper,
) : AuthenticationEntryPoint {
    override fun commence(
        request: HttpServletRequest,
        response: HttpServletResponse,
        authException: AuthenticationException,
    ) {
        val errorCode = (authException as? TokenAuthenticationException)?.errorCode ?: SecurityErrorCode.UNAUTHENTICATED
        val errorResponse = ErrorResponse.from(errorCode)
        response.status = errorCode.status.value()
        val challenge = if (errorCode == SecurityErrorCode.UNAUTHENTICATED) "Bearer" else "Bearer error=\"invalid_token\""
        response.setHeader(HttpHeaders.WWW_AUTHENTICATE, challenge)
        response.contentType = MediaType.APPLICATION_JSON_VALUE
        response.characterEncoding = Charsets.UTF_8.name()
        response.writer.write(jsonMapper.writeValueAsString(errorResponse))
    }
}
