package com.allcheck.chat.domain.entity

import com.allcheck.chat.domain.vo.ChatSide
import com.allcheck.global.exception.BusinessException
import com.allcheck.global.exception.GlobalErrorCode
import java.time.Instant

internal class ChatMessage(
    val id: Long,
    val driverId: Long,
    val sender: ChatSide,
    val body: String,
    val sentAt: Instant,
    val readAt: Instant?,
) {
    companion object {
        fun validateBody(body: String) {
            if (body.isBlank() || '\u0000' in body || body.codePointCount(0, body.length) > 2000) {
                throw BusinessException(GlobalErrorCode.INVALID_INPUT_VALUE)
            }
        }
    }
}
