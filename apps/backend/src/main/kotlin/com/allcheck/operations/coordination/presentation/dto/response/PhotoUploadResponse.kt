package com.allcheck.operations.coordination.presentation.dto.response

import com.allcheck.operations.PhotoPhase
import io.swagger.v3.oas.annotations.media.Schema
import java.time.Instant

data class PhotoUploadResponse(
    @field:Schema(description = "사진 식별자") val photoFileId: String,
    @field:Schema(description = "연결 작업") val workId: String,
    @field:Schema(description = "사진 촬영 단계") val phase: PhotoPhase,
    @field:Schema(description = "직접 업로드 URL. 저장하거나 로그에 남기지 않는다") val uploadUrl: String,
    @field:Schema(description = "서명 만료") val expiresAt: Instant,
    @field:Schema(description = "업로드 메서드") val method: String,
    @field:Schema(description = "변경 없이 전송해야 하는 서명 헤더") val requiredHeaders: Map<String, List<String>>,
)
