package com.allcheck.global.security.handler

import com.allcheck.global.exception.ErrorResponse
import com.allcheck.global.security.exception.SecurityErrorCode
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.http.MediaType
import org.springframework.security.access.AccessDeniedException
import org.springframework.security.web.access.AccessDeniedHandler
import org.springframework.stereotype.Component
import tools.jackson.databind.json.JsonMapper

@Component
class CustomAccessDeniedHandler(
    private val jsonMapper: JsonMapper,
) : AccessDeniedHandler {
    override fun handle(
        request: HttpServletRequest,
        response: HttpServletResponse,
        accessDeniedException: AccessDeniedException,
    ) {
        val errorCode = SecurityErrorCode.ACCESS_DENIED
        val errorResponse = ErrorResponse.from(errorCode)
        response.status = errorCode.status.value()
        response.contentType = MediaType.APPLICATION_JSON_VALUE
        response.characterEncoding = Charsets.UTF_8.name()
        response.writer.write(jsonMapper.writeValueAsString(errorResponse))
    }

    // TODO: Resource Server 채택 시 Bearer 인가 실패의 WWW-Authenticate 헤더 처리 필요 여부를 검토한다.
}
