package com.allcheck.driver.presentation.api

import com.allcheck.driver.domain.vo.DriverAccountStatus
import com.allcheck.driver.presentation.dto.request.DriverApprovalRequest
import com.allcheck.driver.presentation.dto.request.DriverRegistrationRequest
import com.allcheck.driver.presentation.dto.request.DriverResubmissionRequest
import com.allcheck.driver.presentation.dto.request.DriverWithdrawalRequest
import com.allcheck.driver.presentation.dto.response.DriverApprovalResult
import com.allcheck.driver.presentation.dto.response.DriverDetailResponse
import com.allcheck.driver.presentation.dto.response.DriverListResponse
import com.allcheck.driver.presentation.dto.response.DriverRegistrationResult
import com.allcheck.driver.presentation.dto.response.DriverRegistrationView
import com.allcheck.driver.presentation.dto.response.DriverWithdrawalResult
import com.allcheck.global.exception.ErrorResponse
import com.allcheck.global.security.principal.AuthPrincipal
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
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam

@Tag(name = "Drivers")
@SecurityRequirement(name = "bearerAuth")
@RequestMapping("/api/v1/drivers", produces = [MediaType.APPLICATION_JSON_VALUE])
@ApiResponses(
    ApiResponse(responseCode = "400", description = "입력 형식 또는 사업자 검증 실패", content = [Content(schema = Schema(implementation = ErrorResponse::class))]),
    ApiResponse(responseCode = "401", description = "인증 필요", content = [Content(schema = Schema(implementation = ErrorResponse::class))]),
    ApiResponse(responseCode = "403", description = "권한 또는 기사 상태 불충족", content = [Content(schema = Schema(implementation = ErrorResponse::class))]),
    ApiResponse(responseCode = "404", description = "가입 신청 또는 기사 없음", content = [Content(schema = Schema(implementation = ErrorResponse::class))]),
    ApiResponse(responseCode = "409", description = "상태·버전·멱등성 충돌 또는 미완료 작업 존재", content = [Content(schema = Schema(implementation = ErrorResponse::class))]),
    ApiResponse(responseCode = "503", description = "외부 서비스 검증 불가", content = [Content(schema = Schema(implementation = ErrorResponse::class))]),
)
interface DriverApi {
    @GetMapping("/me/registration")
    @PreAuthorize("hasRole('DRIVER')")
    @Operation(summary = "내 가입 신청 상태 조회", operationId = "getMyRegistration")
    fun mine(@Parameter(hidden = true) @AuthenticationPrincipal principal: AuthPrincipal): ResponseEntity<DriverRegistrationView>

    @PostMapping("/me/registration")
    @PreAuthorize("hasRole('DRIVER')")
    @Operation(summary = "기사 가입 신청", operationId = "submitDriverRegistration")
    @ApiResponse(responseCode = "201", description = "신규 가입 신청 접수", headers = [Header(name = "Idempotency-Replayed", description = "동일 요청의 최초 성공 결과 재현 여부", schema = Schema(type = "boolean"))])
    fun submit(
        @Parameter(hidden = true) @AuthenticationPrincipal principal: AuthPrincipal,
        @NotBlank @Size(max = 200) @RequestHeader("Idempotency-Key") key: String,
        @Valid @RequestBody request: DriverRegistrationRequest,
    ): ResponseEntity<DriverRegistrationResult>

    @PostMapping("/me/registration/resubmission")
    @PreAuthorize("hasRole('DRIVER')")
    @Operation(summary = "반려 후 기존 계정으로 재신청", operationId = "resubmitDriverRegistration")
    @ApiResponse(responseCode = "200", description = "기존 기사로 재신청 접수", headers = [Header(name = "Idempotency-Replayed", description = "동일 요청의 최초 성공 결과 재현 여부", schema = Schema(type = "boolean"))])
    fun resubmit(
        @Parameter(hidden = true) @AuthenticationPrincipal principal: AuthPrincipal,
        @NotBlank @Size(max = 200) @RequestHeader("Idempotency-Key") key: String,
        @Valid @RequestBody request: DriverResubmissionRequest,
    ): ResponseEntity<DriverRegistrationResult>

    @GetMapping
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "기사 목록과 신청 상태 조회", operationId = "listDrivers")
    fun list(
        @RequestParam(required = false) accountStatus: DriverAccountStatus?,
        @Size(min = 1, max = 100) @RequestParam(required = false) q: String?,
        @Min(1) @RequestParam(defaultValue = "1") page: Int,
        @Min(1) @Max(100) @RequestParam(defaultValue = "20") size: Int,
    ): ResponseEntity<DriverListResponse>

    @GetMapping("/{driverId}")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "기사 신청 상세와 반려 이력 조회", operationId = "getDriver")
    fun detail(@Size(min = 1, max = 64) @PathVariable driverId: String): ResponseEntity<DriverDetailResponse>

    @PostMapping("/{driverId}/approval")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "기사 가입 승인 또는 반려", operationId = "decideDriverApproval")
    @ApiResponse(responseCode = "200", description = "심사 결과 반영", headers = [Header(name = "Idempotency-Replayed", description = "동일 요청의 최초 성공 결과 재현 여부", schema = Schema(type = "boolean"))])
    fun decide(
        @Parameter(hidden = true) @AuthenticationPrincipal principal: AuthPrincipal,
        @Size(min = 1, max = 64) @PathVariable driverId: String,
        @NotBlank @Size(max = 200) @RequestHeader("Idempotency-Key") key: String,
        @Valid @RequestBody request: DriverApprovalRequest,
    ): ResponseEntity<DriverApprovalResult>

    @PostMapping("/me/withdrawal")
    @PreAuthorize("hasRole('DRIVER')")
    @Operation(summary = "미완료 작업 확인 후 기사 탈퇴", operationId = "withdrawMyAccount")
    @ApiResponse(responseCode = "200", description = "탈퇴 및 전체 세션 폐기", headers = [Header(name = "Idempotency-Replayed", description = "동일 요청의 최초 성공 결과 재현 여부", schema = Schema(type = "boolean"))])
    fun withdraw(
        @Parameter(hidden = true) @AuthenticationPrincipal principal: AuthPrincipal,
        @NotBlank @Size(max = 200) @RequestHeader("Idempotency-Key") key: String,
        @Valid @RequestBody request: DriverWithdrawalRequest,
    ): ResponseEntity<DriverWithdrawalResult>
}
