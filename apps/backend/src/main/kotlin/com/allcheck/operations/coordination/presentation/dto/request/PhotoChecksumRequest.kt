package com.allcheck.operations.coordination.presentation.dto.request

import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Pattern

data class PhotoChecksumRequest(
    @field:Schema(description = "체크섬 알고리즘") @field:Pattern(regexp = "SHA256") val algorithm: String,
    @field:Schema(description = "Base64 인코딩한 32바이트 SHA-256") @field:NotBlank val value: String,
) : StrictOperationsRequest()
