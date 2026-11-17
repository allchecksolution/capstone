package com.allcheck.reception.presentation.api

import com.allcheck.global.exception.ErrorResponse
import com.allcheck.reception.domain.vo.review.ReceptionReviewStatus
import com.allcheck.reception.presentation.dto.request.review.ConfirmReceptionRequest
import com.allcheck.reception.presentation.dto.request.review.CreateReceptionRequest
import com.allcheck.reception.presentation.dto.request.review.ReopenReceptionRequest
import com.allcheck.reception.presentation.dto.response.analysis.ReceptionAnalysisResponse
import com.allcheck.reception.presentation.dto.response.review.ReceptionConfirmedResponse
import com.allcheck.reception.presentation.dto.response.review.ReceptionDetailResponse
import com.allcheck.reception.presentation.dto.response.review.ReceptionListResponse
import com.allcheck.reception.presentation.dto.response.review.ReceptionReopenedResponse
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
import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.core.Authentication
import org.springframework.validation.annotation.Validated
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseStatus

@Validated
@Tag(name = "Receptions", description = "본인 접수와 상담 원본 및 검토 관리")
@SecurityRequirement(name = "bearerAuth")
@RequestMapping("/api/v1/receptions", produces = ["application/json"])
@ApiResponses(
    ApiResponse(responseCode = "400", description = "입력 검증 실패", content = [Content(schema = Schema(implementation = ErrorResponse::class))]),
    ApiResponse(responseCode = "401", description = "인증 필요", content = [Content(schema = Schema(implementation = ErrorResponse::class))]),
    ApiResponse(responseCode = "403", description = "관리자 권한 필요", content = [Content(schema = Schema(implementation = ErrorResponse::class))]),
    ApiResponse(responseCode = "404", description = "접수가 없거나 다른 관리자 소유", content = [Content(schema = Schema(implementation = ErrorResponse::class))]),
    ApiResponse(responseCode = "409", description = "현재 상태·버전·멱등성 충돌", content = [Content(schema = Schema(implementation = ErrorResponse::class))]),
    ApiResponse(responseCode = "503", description = "필요한 관리자 식별 연동 등 서비스 준비 미완료", content = [Content(schema = Schema(implementation = ErrorResponse::class))]),
)
interface ReceptionApi {
    @PostMapping(consumes = ["application/json"])
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(operationId = "createReception", summary = "상담 접수 생성", description = "소유자는 인증 연동에서 정한다. 수동 접수는 빈 객체로 생성할 수 있다.")
    @ApiResponse(responseCode = "201", description = "DRAFT 생성. 같은 키는 최초 성공 응답을 재현한다.", content = [Content(schema = Schema(implementation = ReceptionDetailResponse::class))], headers = [Header(name = "Idempotency-Replayed", schema = Schema(type = "boolean")), Header(name = "Location", schema = Schema(type = "string", format = "uri-reference"))])
    fun create(
        @Parameter(hidden = true) authentication: Authentication,
        @RequestHeader("Idempotency-Key") @NotBlank @Size(max = 200) key: String,
        @Valid @RequestBody request: CreateReceptionRequest,
    ): ResponseEntity<ReceptionDetailResponse>

    @GetMapping
    @Operation(operationId = "listReceptions", summary = "본인 접수 목록", description = "검토 상태 필터와 수신 시각·ID 역순을 적용한다. 페이지와 합계 모두 본인 접수만 계산한다.")
    @ApiResponse(responseCode = "200", description = "처리 결과", content = [Content(schema = Schema(implementation = ReceptionListResponse::class))])
    fun list(
        @Parameter(hidden = true) authentication: Authentication,
        @RequestParam(required = false) reviewStatus: ReceptionReviewStatus?,
        @RequestParam(defaultValue = "1") @Min(1) page: Int,
        @RequestParam(defaultValue = "20") @Min(1) @Max(100) size: Int,
    ): ResponseEntity<ReceptionListResponse>

    @GetMapping("/{receptionId}")
    @Operation(operationId = "getReception", summary = "상담 원본·분석·편집값 상세", description = "STT는 DB 원문 그대로 제공한다. 녹음은 별도 원본 접근 API를 사용한다. reviewEditable이 false이면 편집·검토 완료를 진행하지 않는다.")
    @ApiResponse(responseCode = "200", description = "처리 결과", content = [Content(schema = Schema(implementation = ReceptionDetailResponse::class))])
    fun detail(
        @Parameter(hidden = true) authentication: Authentication,
        @PathVariable @Size(min = 1, max = 64) receptionId: String,
    ): ResponseEntity<ReceptionDetailResponse>

    @GetMapping("/{receptionId}/analyses/{analysisRequestId}")
    @Operation(operationId = "getReceptionAnalysis", summary = "분석 시도 상태", description = "FAILED/DISCARDED도 정상 조회 200이다. 구조화 단계 성공과 전체 성공을 구분한다.")
    @ApiResponse(responseCode = "200", description = "처리 결과", content = [Content(schema = Schema(implementation = ReceptionAnalysisResponse::class))])
    fun analysis(
        @Parameter(hidden = true) authentication: Authentication,
        @PathVariable @Size(min = 1, max = 64) receptionId: String,
        @PathVariable @Size(min = 1, max = 64) analysisRequestId: String,
    ): ResponseEntity<ReceptionAnalysisResponse>

    @PostMapping("/{receptionId}/confirmation", consumes = ["application/json"])
    @Operation(operationId = "confirmReception", summary = "전체 최종값으로 검토 완료", description = "필수값·방문 구간·서버 현재 시각·분석 종료·버전·상태를 확인하여 저장과 확정을 함께 처리한다. 별도 임시 저장은 없다.")
    @ApiResponse(responseCode = "200", description = "처리 결과", content = [Content(schema = Schema(implementation = ReceptionConfirmedResponse::class))], headers = [Header(name = "Idempotency-Replayed", schema = Schema(type = "boolean"))])
    fun confirm(
        @Parameter(hidden = true) authentication: Authentication,
        @PathVariable @Size(min = 1, max = 64) receptionId: String,
        @RequestHeader("Idempotency-Key") @NotBlank @Size(max = 200) key: String,
        @Valid @RequestBody request: ConfirmReceptionRequest,
    ): ResponseEntity<ReceptionConfirmedResponse>

    @PostMapping("/{receptionId}/reopen", consumes = ["application/json"])
    @Operation(operationId = "reopenReception", summary = "의뢰 등록 전 검토 확정 취소", description = "원본과 최신값을 보존하고 DRAFT로 돌아간다. AI를 다시 실행하지 않는다.")
    @ApiResponse(responseCode = "200", description = "처리 결과", content = [Content(schema = Schema(implementation = ReceptionReopenedResponse::class))], headers = [Header(name = "Idempotency-Replayed", schema = Schema(type = "boolean"))])
    fun reopen(
        @Parameter(hidden = true) authentication: Authentication,
        @PathVariable @Size(min = 1, max = 64) receptionId: String,
        @RequestHeader("Idempotency-Key") @NotBlank @Size(max = 200) key: String,
        @Valid @RequestBody request: ReopenReceptionRequest,
    ): ResponseEntity<ReceptionReopenedResponse>
}
