package com.allcheck.operations.coordination.presentation.dto.response

import com.allcheck.operations.OfferStatus
import com.allcheck.operations.WorkType
import io.swagger.v3.oas.annotations.media.Schema
import java.time.Instant
import java.time.OffsetDateTime

data class DispatchOfferResponse(
    @field:Schema(description = "기사별 논리적 제안 식별자") val offerId: String,
    @field:Schema(description = "의뢰 식별자") val requestId: String,
    @field:Schema(description = "회차 식별자") val dispatchRoundId: String,
    @field:Schema(description = "응답 상태") val offerStatus: OfferStatus,
    @field:Schema(description = "FCM 수락과 실제 수신을 구분하는 전송 집계") val deliveryStatus: String,
    @field:Schema(description = "작업 유형") val workType: WorkType,
    @field:Schema(description = "확정 증상") val symptoms: String,
    @field:Schema(description = "시군구와 읍면동까지만 공개한 지역") val addressArea: String,
    @field:Schema(description = "후보 선정 시 직선거리 미터") val distanceMeters: Double,
    @field:Schema(description = "확정 방문 시작") val desiredVisitFrom: OffsetDateTime,
    @field:Schema(description = "확정 방문 종료") val desiredVisitTo: OffsetDateTime,
    @field:Schema(description = "특이사항") val notes: String?,
    @field:Schema(description = "제안 생성 시각") val createdAt: Instant,
    @field:Schema(description = "연장되지 않는 응답 만료 시각") val expiresAt: Instant,
    @field:Schema(description = "서버 기준 현재 응답 가능 여부") val canRespond: Boolean,
    @field:Schema(description = "최초 실제 수신 신호") val receivedAt: Instant?,
    @field:Schema(description = "최초 실제 열람 신호") val viewedAt: Instant?,
)
