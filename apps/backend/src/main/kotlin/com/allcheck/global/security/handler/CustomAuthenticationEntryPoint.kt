package com.allcheck.global.security.handler

import com.allcheck.global.exception.ErrorResponse
import com.allcheck.global.security.exception.SecurityErrorCode
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
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
        val errorCode = SecurityErrorCode.UNAUTHENTICATED
        val errorResponse = ErrorResponse.from(errorCode)
        response.status = errorCode.status.value()
        response.contentType = MediaType.APPLICATION_JSON_VALUE
        response.characterEncoding = Charsets.UTF_8.name()
        response.writer.write(jsonMapper.writeValueAsString(errorResponse))
    }

    // TODO: JWT 인증 방식 확정 후 만료·변조 예외를 공통 ErrorResponse로 매핑한다.
    // TODO: Resource Server 채택 시 BearerTokenAuthenticationEntryPoint의 WWW-Authenticate 헤더를 보존한다.
}
