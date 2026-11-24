package com.allcheck.reception.presentation.api

import com.allcheck.global.exception.ErrorResponse
import com.allcheck.global.security.principal.AuthPrincipal
import com.allcheck.reception.presentation.dto.request.recording.CompleteRecordingUploadRequest
import com.allcheck.reception.presentation.dto.request.recording.RecordingUploadRequest
import com.allcheck.reception.presentation.dto.response.recording.RecordingAccessResponse
import com.allcheck.reception.presentation.dto.response.recording.RecordingUploadCompletedResponse
import com.allcheck.reception.presentation.dto.response.recording.RecordingUploadGrantResponse
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.headers.Header
import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import io.swagger.v3.oas.annotations.security.SecurityRequirement
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.validation.annotation.Validated
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus

@Validated
@Tag(name = "Files", description = "본인 접수의 녹음 검증과 단기 원본 접근")
@SecurityRequirement(name = "bearerAuth")
@RequestMapping("/api/v1/receptions/{receptionId}/recordings", produces = ["application/json"])
@ApiResponses(
    ApiResponse(responseCode = "400", description = "입력 또는 파일 검증 실패", content = [Content(schema = Schema(implementation = ErrorResponse::class))]),
    ApiResponse(responseCode = "401", description = "인증 필요", content = [Content(schema = Schema(implementation = ErrorResponse::class))]),
    ApiResponse(responseCode = "403", description = "관리자 권한 필요", content = [Content(schema = Schema(implementation = ErrorResponse::class))]),
    ApiResponse(responseCode = "404", description = "본인 접수 또는 연결된 녹음이 아님", content = [Content(schema = Schema(implementation = ErrorResponse::class))]),
    ApiResponse(responseCode = "409", description = "버전·멱등성 충돌 또는 미완료 원본", content = [Content(schema = Schema(implementation = ErrorResponse::class))]),
    ApiResponse(responseCode = "415", description = "WAV 외의 업로드 형식", content = [Content(schema = Schema(implementation = ErrorResponse::class))]),
    ApiResponse(responseCode = "503", description = "녹음 저장소 사용 불가", content = [Content(schema = Schema(implementation = ErrorResponse::class))]),
)
interface ReceptionRecordingApi {
    @PostMapping("/upload-requests", consumes = ["application/json"])
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(operationId = "createRecordingUploadRequest", summary = "WAV 녹음 업로드 예약", description = "원본 파일명에서 모호하지 않은 연락처를 추출한다. 같은 키는 같은 예약을 사용하며 URL은 DB에 저장하지 않고 매번 발급한다.")
    @ApiResponse(responseCode = "201", description = "예약과 새로운 단기 업로드 URL", content = [Content(schema = Schema(implementation = RecordingUploadGrantResponse::class))], headers = [Header(name = "Idempotency-Replayed", description = "예약 재사용 여부. URL 재사용 여부가 아니다.", schema = Schema(type = "boolean"))])
    fun upload(
        @Parameter(hidden = true) @AuthenticationPrincipal principal: AuthPrincipal?,
        @PathVariable receptionId: String,
        @RequestHeader("Idempotency-Key") @NotBlank @Size(max = 200) key: String,
        @Valid @RequestBody request: RecordingUploadRequest,
    ): ResponseEntity<RecordingUploadGrantResponse>

    @PostMapping("/{recordingFileId}/upload-requests")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(operationId = "renewRecordingUploadRequest", summary = "미완료 녹음 업로드 URL 발급", description = "기존 파일 식별자·객체·메타데이터를 유지하고 매번 새 URL을 발급한다. 완료된 파일은 재업로드할 수 없다.")
    @ApiResponse(responseCode = "201", description = "기존 예약과 새로운 단기 업로드 URL", content = [Content(schema = Schema(implementation = RecordingUploadGrantResponse::class))], headers = [Header(name = "Idempotency-Replayed", description = "명령 중복 여부. URL은 매번 새로 발급한다.", schema = Schema(type = "boolean"))])
    fun renewUpload(
        @Parameter(hidden = true) @AuthenticationPrincipal principal: AuthPrincipal?,
        @PathVariable receptionId: String,
        @PathVariable recordingFileId: String,
        @RequestHeader("Idempotency-Key") @NotBlank @Size(max = 200) key: String,
    ): ResponseEntity<RecordingUploadGrantResponse>

    @GetMapping("/{recordingFileId}/access")
    @Operation(operationId = "getRecordingAccess", summary = "녹음 원본 재생 URL 발급", description = "소유권과 업로드 완료를 확인한다. 상세의 recordingFileId를 사용하며 객체 키를 받지 않는다. 만료 시 재발급한다.")
    @ApiResponse(responseCode = "200", description = "단기 Presigned GET URL", content = [Content(schema = Schema(implementation = RecordingAccessResponse::class))], headers = [Header(name = "Cache-Control", schema = Schema(type = "string", allowableValues = ["no-store"]))])
    fun access(
        @Parameter(hidden = true) @AuthenticationPrincipal principal: AuthPrincipal?,
        @PathVariable receptionId: String,
        @PathVariable recordingFileId: String,
    ): ResponseEntity<RecordingAccessResponse>

    @PostMapping("/{recordingFileId}/completion", consumes = ["application/json"])
    @ResponseStatus(HttpStatus.ACCEPTED)
    @Operation(operationId = "completeRecordingUpload", summary = "녹음 업로드 검증 및 최초 분석 접수", description = "실제 S3 객체의 MIME·크기·SHA256을 확인한 뒤 분석 의도를 함께 커밋한다. 최초 완료 시각과 분석 식별자는 재통보로 바뀌지 않는다.")
    @ApiResponse(responseCode = "202", description = "분석 접수. 분석 완료는 아니다.", content = [Content(schema = Schema(implementation = RecordingUploadCompletedResponse::class))], headers = [Header(name = "Location", schema = Schema(type = "string", format = "uri-reference")), Header(name = "Idempotency-Replayed", schema = Schema(type = "boolean"))])
    fun complete(
        @Parameter(hidden = true) @AuthenticationPrincipal principal: AuthPrincipal?,
        @PathVariable receptionId: String,
        @PathVariable recordingFileId: String,
        @RequestHeader("Idempotency-Key") @NotBlank @Size(max = 200) key: String,
        @Valid @RequestBody request: CompleteRecordingUploadRequest,
    ): ResponseEntity<RecordingUploadCompletedResponse>
}
