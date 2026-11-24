package com.allcheck.operations.coordination.presentation.dto.response

import io.swagger.v3.oas.annotations.media.Schema
import java.time.Instant

data class OperationResponse(
    @field:Schema(description = "이번 명령으로 생성 또는 변경한 자원 식별자") val resourceId: String,
    @field:Schema(description = "관련 의뢰 식별자") val requestId: String,
    @field:Schema(description = "이번 명령의 결과 상태. 최신 상태는 GET으로 확인") val status: String,
    @field:Schema(description = "변경한 자원의 업무 버전. 사진은 관련 작업 버전") val version: Int,
    @field:Schema(description = "명령 시각. 사진 완료에서는 최초 업로드 완료 시각") val at: Instant,
    @field:Schema(description = "생성된 배차 회차 또는 관련 작업/제안 식별자") val relatedResourceId: String?,
)
