package com.allcheck.chat.domain.entity

import com.allcheck.chat.domain.vo.ChatReadState
import com.allcheck.chat.domain.vo.ChatSide
import java.time.Instant

internal class Conversation(
    val id: Long,
    val driverId: Long,
    private val adminRead: ChatReadState?,
    private val driverRead: ChatReadState?,
) {
    fun readState(side: ChatSide): ChatReadState? = if (side == ChatSide.ADMIN) adminRead else driverRead

    fun readThrough(side: ChatSide, messageId: Long, now: Instant): ChatReadState = readState(side)?.takeIf { messageId <= it.messageId } ?: ChatReadState(messageId, now)
}
