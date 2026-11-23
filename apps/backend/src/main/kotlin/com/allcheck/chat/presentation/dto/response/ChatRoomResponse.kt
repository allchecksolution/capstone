package com.allcheck.chat.presentation.dto.response

import io.swagger.v3.oas.annotations.media.Schema

internal data class ChatRoomResponse(
    val driver: ChatRoomDriverResponse,
    @field:Schema(description = "기사의 현재 계정 상태", allowableValues = ["PENDING_APPROVAL", "ACTIVATED", "REJECTED", "WITHDRAWN"])
    val driverAccountStatus: String,
    val lastMessage: ChatMessageResponse,
    @field:Schema(description = "관리자 측 공유 읽음 기준으로 읽지 않은 기사 메시지 수")
    val unreadCount: Long,
    @field:Schema(description = "이력 열람만 가능하고 메시지 전송과 읽음 갱신이 차단되는지 여부")
    val readOnly: Boolean,
)
