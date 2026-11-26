package com.allcheck.operations.coordination.presentation.dto.response

import io.swagger.v3.oas.annotations.media.Schema
import java.time.Instant

data class PhotoAccessResponse(
    @field:Schema(description = "사진 식별자") val photoFileId: String,
    @field:Schema(description = "단기 읽기 URL") val downloadUrl: String,
    @field:Schema(description = "서명 만료") val expiresAt: Instant,
    @field:Schema(description = "읽기 메서드") val method: String,
    @field:Schema(description = "검증된 이미지 형식") val contentType: String,
)
