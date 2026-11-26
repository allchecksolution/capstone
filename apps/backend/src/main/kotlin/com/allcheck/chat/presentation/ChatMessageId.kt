package com.allcheck.chat.presentation

import com.allcheck.global.exception.BusinessException
import com.allcheck.global.exception.GlobalErrorCode

internal fun chatMessageId(id: Long) = "msg_$id"

internal fun parseChatMessageId(value: String): Long {
    if (value.startsWith("msg_")) {
        value.substring(4).toLongOrNull()?.takeIf { it > 0 && chatMessageId(it) == value }?.let { return it }
    }
    throw BusinessException(GlobalErrorCode.INVALID_INPUT_VALUE)
}
