package com.allcheck.reception.presentation.dto.response

import io.swagger.v3.oas.annotations.media.Schema
import java.time.Instant

@Schema(name = "RecordingUploadGrant", description = "예약은 중복 생성하지 않고 URL은 매번 새로 발급한다. URL은 DB에 보관하지 않는다.")
class RecordingUploadGrantResponse(
    @field:Schema(description = "예약한 원본 녹음 식별자") val recordingFileId: String,
    @field:Schema(description = "접수 식별자") val receptionId: String,
    @field:Schema(description = "예약 후 업무 버전") val version: Int,
    @field:Schema(description = "단기 Presigned PUT URL. 저장·로그 기록 금지.", format = "uri") val uploadUrl: String,
    @field:Schema(description = "서명에 포함된 헤더. Content-Type, Content-Length, If-None-Match, SHA256을 그대로 전송한다.") val requiredHeaders: Map<String, String>,
    @field:Schema(description = "이 응답에서 새로 발급한 서명 만료 시각") val expiresAt: Instant,
    @field:Schema(description = "미완료 예약", allowableValues = ["PENDING"]) val fileStatus: String = "PENDING",
    @field:Schema(description = "S3 직접 업로드 메서드", allowableValues = ["PUT"]) val method: String = "PUT",
)
