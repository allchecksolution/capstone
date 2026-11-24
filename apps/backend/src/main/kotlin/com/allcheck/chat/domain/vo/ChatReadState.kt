package com.allcheck.chat.domain.vo

import java.time.Instant

internal data class ChatReadState(val messageId: Long, val readAt: Instant)
