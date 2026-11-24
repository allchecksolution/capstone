package com.allcheck.operations.coordination.presentation.dto.request

import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import tools.jackson.databind.annotation.JsonDeserialize

data class SubmitReportRequest(
    @field:Schema(description = "현재 작업 버전") @field:Min(1) val expectedVersion: Int,
    @field:Schema(description = "실제 수행한 작업") @field:NotBlank val workDescription: String,
    @field:Schema(description = "최종 진단") @field:NotBlank val diagnosis: String,
    @field:Schema(description = "원 단위 총액") @field:Min(0) @field:JsonDeserialize(using = IntegerMoneyDeserializer::class) val totalCost: Long,
    @field:Schema(description = "완료 검증된 BEFORE 사진") @field:Size(min = 3, max = 5) val beforePhotoIds: List<@NotBlank String>,
    @field:Schema(description = "완료 검증된 AFTER 사진") @field:Size(min = 3, max = 5) val afterPhotoIds: List<@NotBlank String>,
) : StrictOperationsRequest()
