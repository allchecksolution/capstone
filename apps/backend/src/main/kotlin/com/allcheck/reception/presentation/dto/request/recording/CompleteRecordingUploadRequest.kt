package com.allcheck.reception.presentation.dto.request.recording

import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.constraints.Min

@Schema(name = "CompleteRecordingUpload")
data class CompleteRecordingUploadRequest(@field:Min(1) @field:Schema(description = "녹음 예약 후 상세에서 확인한 업무 버전") val expectedVersion: Int)
