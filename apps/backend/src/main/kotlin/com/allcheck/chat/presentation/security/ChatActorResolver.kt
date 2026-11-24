package com.allcheck.chat.presentation.security

import com.allcheck.chat.domain.vo.ChatActor
import com.allcheck.chat.domain.vo.ChatSide
import com.allcheck.chat.exception.ChatErrorCode
import com.allcheck.global.exception.BusinessException
import com.allcheck.global.security.principal.AuthPrincipal
import com.allcheck.identity.UserType

internal fun AuthPrincipal?.toChatActor(): ChatActor {
    val principal = this ?: throw BusinessException(ChatErrorCode.CHAT_NOT_ALLOWED)
    val side = when (principal.userType) {
        UserType.ADMIN -> ChatSide.ADMIN
        UserType.DRIVER -> ChatSide.DRIVER
    }
    return ChatActor(principal.accountId, side)
}
