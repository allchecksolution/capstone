package com.allcheck.chat.exception

import com.allcheck.global.exception.ErrorCode
import org.springframework.http.HttpStatus

internal enum class ChatErrorCode(override val status: HttpStatus, override val message: String) : ErrorCode {
    CHAT_NOT_FOUND(HttpStatus.NOT_FOUND, "채팅방을 찾을 수 없습니다."),
    CHAT_NOT_ALLOWED(HttpStatus.FORBIDDEN, "현재 채팅을 이용할 수 없습니다."),
}
