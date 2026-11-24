package com.allcheck.chat.presentation.dto.response

import java.time.Instant

internal data class ChatReadResponse(val lastReadMessageId: String, val readAt: Instant)
