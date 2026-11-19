package com.allcheck.operations.coordination.presentation.dto

import com.allcheck.global.exception.BusinessException
import com.allcheck.global.exception.GlobalErrorCode
import com.allcheck.operations.CancellationReason
import com.allcheck.operations.FollowUpChoice
import com.allcheck.operations.FollowUpTarget
import com.allcheck.operations.PhotoPhase
import com.allcheck.operations.WorkStatus
import com.allcheck.operations.validInput
import com.allcheck.operations.work.PhotoMetadata
import com.fasterxml.jackson.annotation.JsonAnySetter
import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.Valid
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Pattern
import jakarta.validation.constraints.Size
import tools.jackson.databind.JsonNode
import tools.jackson.databind.annotation.JsonDeserialize
import java.time.Instant

data class RegisterServiceRequest(
    @field:Schema(description = "검토 완료된 본인 접수") @field:NotBlank val receptionId: String,
    @field:Schema(description = "접수 확정 버전") @field:Min(1) val expectedVersion: Int,
) : StrictOperationsRequest()
data class RespondOfferRequest(@field:Schema(description = "ACCEPT 또는 REJECT") @field:Pattern(regexp = "ACCEPT|REJECT") val response: String) : StrictOperationsRequest()
data class TransitionWorkRequest(
    @field:Schema(description = "현재 작업 버전") @field:Min(1) val expectedVersion: Int,
    @field:Schema(description = "진행 또는 재방문 대상 상태. 완료·취소는 전용 API") val targetStatus: WorkStatus,
    @field:Schema(description = "재방문 전환 시 필수 사유") val reason: String? = null,
    @field:Schema(description = "예정 재방문 시각. 미정 허용") val plannedRevisitAt: Instant? = null,
) : StrictOperationsRequest()
data class RevisitPlanRequest(
    @field:Schema(description = "현재 작업 버전") @field:Min(1) val expectedVersion: Int,
    @field:Schema(description = "예정 재방문 시각. null이면 미정으로 변경") val plannedRevisitAt: Instant? = null,
) : StrictOperationsRequest()
data class CancelWorkRequest(
    @field:Schema(description = "현재 작업 버전") @field:Min(1) val expectedVersion: Int,
    @field:Schema(description = "취소 사유 분류") val reasonCode: CancellationReason,
    @field:Schema(description = "모든 사유에서 필수. 최대 1000 유니코드 코드포인트") @field:NotBlank val reasonDetail: String,
) : StrictOperationsRequest()
data class FollowUpTargetRequest(
    @field:Schema(description = "취소 대상. 회차 대상과 양자택일") val cancellationId: String? = null,
    @field:Schema(description = "배차 회차 대상. 취소 대상과 양자택일") val dispatchRoundId: String? = null,
) : StrictOperationsRequest() {
    fun target(): FollowUpTarget {
        validInput((cancellationId == null) != (dispatchRoundId == null))
        return if (cancellationId != null) FollowUpTarget("CANCELLATION", OperationsId.CANCELLATION.parse(cancellationId)) else FollowUpTarget("ROUND", OperationsId.ROUND.parse(checkNotNull(dispatchRoundId)))
    }
}
data class FollowUpDecisionRequest(
    @field:Schema(description = "현재 의뢰 버전") @field:Min(1) val expectedVersion: Int,
    @field:Schema(description = "현재 미해결 대상") @field:Valid val target: FollowUpTargetRequest,
    @field:Schema(description = "재배차 또는 의뢰 취소") val decision: FollowUpChoice,
) : StrictOperationsRequest()
data class SubmitReportRequest(
    @field:Schema(description = "현재 작업 버전") @field:Min(1) val expectedVersion: Int,
    @field:Schema(description = "실제 수행한 작업") @field:NotBlank val workDescription: String,
    @field:Schema(description = "최종 진단") @field:NotBlank val diagnosis: String,
    @field:Schema(description = "원 단위 총액") @field:Min(0) @field:JsonDeserialize(using = IntegerMoneyDeserializer::class) val totalCost: Long,
    @field:Schema(description = "완료 검증된 BEFORE 사진") @field:Size(min = 3, max = 5) val beforePhotoIds: List<@NotBlank String>,
    @field:Schema(description = "완료 검증된 AFTER 사진") @field:Size(min = 3, max = 5) val afterPhotoIds: List<@NotBlank String>,
) : StrictOperationsRequest()
data class ReportChangesRequest(
    @field:Schema(description = "변경할 작업 내용") val workDescription: String? = null,
    @field:Schema(description = "변경할 진단") val diagnosis: String? = null,
    @field:Schema(description = "변경할 원 단위 총액") @field:Min(0) @field:JsonDeserialize(using = IntegerMoneyDeserializer::class) val totalCost: Long? = null,
) : StrictOperationsRequest()
data class ReviseReportRequest(
    @field:Schema(description = "현재 보고서 버전") @field:Min(1) val expectedVersion: Int,
    @field:Schema(description = "필수 수정 사유") @field:NotBlank val reason: String,
    @field:Schema(description = "허용된 내용 필드 변경") @field:Valid val changes: ReportChangesRequest,
) : StrictOperationsRequest()
data class PhotoChecksumRequest(
    @field:Schema(description = "체크섬 알고리즘") @field:Pattern(regexp = "SHA256") val algorithm: String,
    @field:Schema(description = "Base64 인코딩한 32바이트 SHA-256") @field:NotBlank val value: String,
) : StrictOperationsRequest()
data class WorkPhotoUploadRequest(
    @field:Schema(description = "BEFORE 또는 AFTER") val phase: PhotoPhase,
    @field:Schema(description = "원본 파일명") @field:NotBlank @field:Size(max = 255) val originalFilename: String,
    @field:Schema(description = "JPEG 또는 PNG MIME") @field:Pattern(regexp = "image/jpeg|image/png") val contentType: String,
    @field:Schema(description = "이미지 바이트 수") @field:Min(1) @field:jakarta.validation.constraints.Max(10_000_000) val sizeBytes: Long,
    @field:Schema(description = "직접 업로드 검증에 사용할 체크섬") @field:Valid val checksum: PhotoChecksumRequest,
) : StrictOperationsRequest() {
    fun metadata() = PhotoMetadata(phase, originalFilename, contentType, sizeBytes, checksum.value)
}

enum class OperationsId(private val prefix: String) {
    RECEPTION("rec"),
    REQUEST("req"),
    ROUND("round"),
    OFFER("offer"),
    WORK("work"),
    CANCELLATION("cancel"),
    REPORT("report"),
    PHOTO("photo"),
    DECISION("decision"),
    ;

    fun format(id: Long) = "${prefix}_$id"
    fun parse(value: String): Long {
        val number = value.removePrefix("${prefix}_")
        return number.toLongOrNull()?.takeIf { value.startsWith("${prefix}_") && it > 0 && number.all { digit -> digit in '0'..'9' } }
            ?: throw BusinessException(GlobalErrorCode.INVALID_INPUT_VALUE)
    }
}

abstract class StrictOperationsRequest {
    @JsonAnySetter
    @Suppress("UNUSED_PARAMETER")
    fun rejectUnknown(field: String, value: JsonNode?): Unit = throw BusinessException(GlobalErrorCode.INVALID_INPUT_VALUE)
}
