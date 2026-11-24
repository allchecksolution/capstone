package com.allcheck.chat.domain.vo

internal enum class ChatSide {
    ADMIN,
    DRIVER,
    ;

    fun opposite(): ChatSide = if (this == ADMIN) DRIVER else ADMIN
}
