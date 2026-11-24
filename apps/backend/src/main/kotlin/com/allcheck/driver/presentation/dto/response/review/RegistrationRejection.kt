package com.allcheck.driver.presentation.dto.response.review

import io.swagger.v3.oas.annotations.media.Schema
import java.time.Instant

data class RegistrationRejection(
    @field:Schema(description = "관리자가 입력한 반려 사유") val reason: String,
    @field:Schema(description = "반려 시각") val rejectedAt: Instant,
)
