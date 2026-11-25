package com.allcheck.operations.coordination.presentation.dto.request

import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.constraints.Min
import tools.jackson.databind.annotation.JsonDeserialize

data class ReportChangesRequest(
    @field:Schema(description = "변경할 작업 내용") val workDescription: String? = null,
    @field:Schema(description = "변경할 진단") val diagnosis: String? = null,
    @field:Schema(description = "변경할 원 단위 총액") @field:Min(0) @field:JsonDeserialize(using = IntegerMoneyDeserializer::class) val totalCost: Long? = null,
) : StrictOperationsRequest()
