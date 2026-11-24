package com.allcheck.operations.coordination.presentation.dto.request

import com.allcheck.operations.PhotoPhase
import com.allcheck.operations.work.PhotoMetadata
import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.Valid
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Pattern
import jakarta.validation.constraints.Size

data class WorkPhotoUploadRequest(
    @field:Schema(description = "BEFORE 또는 AFTER") val phase: PhotoPhase,
    @field:Schema(description = "원본 파일명") @field:NotBlank @field:Size(max = 255) val originalFilename: String,
    @field:Schema(description = "JPEG 또는 PNG MIME") @field:Pattern(regexp = "image/jpeg|image/png") val contentType: String,
    @field:Schema(description = "이미지 바이트 수") @field:Min(1) @field:jakarta.validation.constraints.Max(10_000_000) val sizeBytes: Long,
    @field:Schema(description = "직접 업로드 검증에 사용할 체크섬") @field:Valid val checksum: PhotoChecksumRequest,
) : StrictOperationsRequest() {
    fun metadata() = PhotoMetadata(phase, originalFilename, contentType, sizeBytes, checksum.value)
}
