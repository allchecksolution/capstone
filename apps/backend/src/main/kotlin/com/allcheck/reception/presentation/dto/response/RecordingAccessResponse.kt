package com.allcheck.reception.presentation.dto.response

import io.swagger.v3.oas.annotations.media.Schema
import java.time.Instant

/** URL은 응답에서만 제공하며 자동 생성 toString에 포함하지 않는다. */
@Schema(name = "RecordingAccess")
class RecordingAccessResponse(
    @field:Schema(description = "경로 접수에 연결된 녹음 식별자") val fileId: String,
    @field:Schema(description = "단기 Presigned URL. 저장하거나 로그에 남기지 않는다.", format = "uri") val url: String,
    @field:Schema(description = "서명 만료 시각. 자격증명/버킷 정책에 의해 더 일찍 만료될 수 있다.") val expiresAt: Instant,
    @field:Schema(description = "검증된 녹음 MIME") val contentType: String,
    @field:Schema(description = "녹음 원본", allowableValues = ["CALL_RECORDING"]) val kind: String = "CALL_RECORDING",
    @field:Schema(description = "원본 접근 메서드", allowableValues = ["GET"]) val method: String = "GET",
)
