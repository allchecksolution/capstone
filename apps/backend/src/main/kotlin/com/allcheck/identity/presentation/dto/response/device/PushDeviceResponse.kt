package com.allcheck.identity.presentation.dto.response.device

import io.swagger.v3.oas.annotations.media.Schema
import java.time.Instant

data class PushDeviceResponse(
    @field:Schema(description = "앱 설치 식별자") val installationId: String,
    @field:Schema(description = "앱 플랫폼") val platform: String,
    @field:Schema(description = "현재 등록·갱신 시각") val registeredAt: Instant,
)
