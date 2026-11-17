package com.allcheck.reception.presentation.dto.request.review

import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.constraints.Min

@Schema(name = "ReopenReception", description = "의뢰 등록 전 검토 확정을 취소한다.")
data class ReopenReceptionRequest(
    @field:Min(1)
    @field:Schema(description = "마지막 조회에서 받은 접수 업무 버전")
    val expectedVersion: Int,
)
