package com.allcheck.chat.infrastructure

import com.allcheck.chat.domain.vo.ChatSide

internal data class ChatMessageEvent(val messageId: Long, val driverId: Long, val sender: ChatSide, val read: Boolean)

internal data class ChatPushDelivery(
    val id: Long,
    val messageId: Long,
    val driverId: Long,
    val recipientSide: ChatSide,
    val accountId: Long,
    val installationId: String,
    val attempt: Int,
)

internal enum class PushDeliveryStatus { PENDING, PROVIDER_ACCEPTED, FAILED, SKIPPED }
