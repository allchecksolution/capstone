package com.allcheck.reception.presentation.dto.request

import com.allcheck.global.exception.BusinessException
import com.allcheck.global.exception.GlobalErrorCode
import com.allcheck.reception.application.RecordingUploadMetadata
import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.Valid
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Pattern
import java.util.Base64

@Schema(name = "RecordingUploadRequest", description = "원본 파일명을 그대로 전달한다. Backend가 연락처 추출과 WAV 형식 검증을 수행한다.")
data class RecordingUploadRequest(
    @field:Min(1) @field:Schema(description = "상세 조회의 업무 버전") val expectedVersion: Int,
    @field:NotBlank @field:Schema(description = "확장자를 포함한 녹음 원본 파일명. 전화번호를 추출하므로 변경/생략하지 않는다.", maxLength = 255) val originalFilename: String,
    @field:NotBlank @field:Schema(description = "WAV MIME. 원본 업로드에도 동일한 Content-Type을 사용한다.", allowableValues = ["audio/wav", "audio/x-wav", "audio/wave", "audio/vnd.wave"]) val contentType: String,
    @field:Min(1) @field:Schema(description = "원본의 바이트 크기. 최대값은 별도 확정 전이다.") val sizeBytes: Long,
    @field:Valid @field:Schema(description = "원본 전체 SHA256") val checksum: RecordingChecksumRequest,
) {
    fun toMetadata(): RecordingUploadMetadata {
        val digest = try {
            Base64.getDecoder().decode(checksum.value)
        } catch (_: IllegalArgumentException) {
            throw BusinessException(GlobalErrorCode.INVALID_INPUT_VALUE)
        }
        if (checksum.algorithm != "SHA256" || digest.size != 32) throw BusinessException(GlobalErrorCode.INVALID_INPUT_VALUE)
        return RecordingUploadMetadata(originalFilename, contentType, sizeBytes, digest)
    }
}

data class RecordingChecksumRequest(
    @field:Pattern(regexp = "SHA256") @field:Schema(description = "고정 해시 알고리즘", allowableValues = ["SHA256"]) val algorithm: String,
    @field:Pattern(regexp = "[A-Za-z0-9+/]{43}=") @field:Schema(description = "전체 digest 32바이트의 Base64. hex가 아니다.") val value: String,
)
