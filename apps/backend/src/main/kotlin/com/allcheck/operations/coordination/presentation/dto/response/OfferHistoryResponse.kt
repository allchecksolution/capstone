package com.allcheck.operations.coordination.presentation.dto.response

import com.allcheck.operations.OfferStatus
import io.swagger.v3.oas.annotations.media.Schema
import java.time.Instant

data class OfferHistoryResponse(
    @field:Schema(description = "제안 식별자") val offerId: String,
    @field:Schema(description = "기사 식별자") val driverId: String,
    @field:Schema(description = "응답 상태") val status: OfferStatus,
    @field:Schema(description = "전송 집계") val deliveryStatus: String,
    @field:Schema(description = "응답 시각") val respondedAt: Instant?,
    @field:Schema(description = "고정 만료 시각") val expiresAt: Instant,
    @field:Schema(description = "제안 생성 시각") val createdAt: Instant,
    @field:Schema(description = "기기의 최초 실제 수신 신호. 공급자 성공만으로 채우지 않는다") val receivedAt: Instant?,
    @field:Schema(description = "최초 실제 열람 신호") val viewedAt: Instant?,
)
