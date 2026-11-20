package com.allcheck.driver.presentation.api.registration

import com.allcheck.driver.domain.vo.registration.DriverAccountStatus
import com.allcheck.driver.presentation.dto.request.registration.BusinessVerificationRequest
import com.allcheck.driver.presentation.dto.request.registration.DriverRegistrationRequest
import com.allcheck.driver.presentation.dto.request.registration.DriverResubmissionRequest
import com.allcheck.driver.presentation.dto.request.review.DriverApprovalRequest
import com.allcheck.driver.presentation.dto.request.withdrawal.DriverWithdrawalRequest
import com.allcheck.driver.presentation.dto.response.query.DriverDetailResponse
import com.allcheck.driver.presentation.dto.response.query.DriverListResponse
import com.allcheck.driver.presentation.dto.response.registration.BusinessVerificationResponse
import com.allcheck.driver.presentation.dto.response.registration.DriverRegistrationResult
import com.allcheck.driver.presentation.dto.response.registration.DriverRegistrationView
import com.allcheck.driver.presentation.dto.response.review.DriverApprovalResult
import com.allcheck.driver.presentation.dto.response.withdrawal.DriverWithdrawalResult
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
    @PostMapping("/business-verifications")
    @PreAuthorize("hasRole('DRIVER')")
    @Operation(summary = "가입 입력 중 사업자 진위확인", description = "계속사업자 여부를 확인하며 신청은 생성하지 않는다. 최종 가입·재신청 때 다시 검증한다.", operationId = "verifyDriverBusiness")
    @ApiResponse(responseCode = "200", description = "진위 일치 및 계속사업자 확인")
    fun verifyBusiness(
        @Parameter(hidden = true) @AuthenticationPrincipal principal: AuthPrincipal,
        @Valid @RequestBody request: BusinessVerificationRequest,
    ): ResponseEntity<BusinessVerificationResponse>

    @GetMapping("/me/registration")
    @PreAuthorize("hasRole('DRIVER')")
    @Operation(summary = "내 가입 신청 상태 조회", operationId = "getMyRegistration")
    fun mine(@Parameter(hidden = true) @AuthenticationPrincipal principal: AuthPrincipal): ResponseEntity<DriverRegistrationView>

    @PostMapping("/me/registration")
    @PreAuthorize("hasRole('DRIVER')")
    @Operation(summary = "기사 가입 신청", description = "가입·재신청 API 합산 계정별 첫 요청부터 60초 동안 최대 5회. 업무 실패와 멱등 재전송도 집계.", operationId = "submitDriverRegistration")
    @ApiResponses(
        ApiResponse(responseCode = "201", description = "신규 가입 신청 접수", headers = [Header(name = "Idempotency-Replayed", description = "동일 요청의 최초 성공 결과 재현 여부", schema = Schema(type = "boolean"))]),
        ApiResponse(responseCode = "429", description = "계정별 가입·재신청 합산 요청 한도 초과", headers = [Header(name = "Retry-After", description = "다시 요청하기까지 남은 초", schema = Schema(type = "integer", format = "int64", minimum = "1"))], content = [Content(schema = Schema(implementation = ErrorResponse::class))]),
    )
    fun submit(
        @Parameter(hidden = true) @AuthenticationPrincipal principal: AuthPrincipal,
        @NotBlank @Size(max = 200) @RequestHeader("Idempotency-Key") key: String,
        @Valid @RequestBody request: DriverRegistrationRequest,
    ): ResponseEntity<DriverRegistrationResult>

    @PostMapping("/me/registration/resubmission")
    @PreAuthorize("hasRole('DRIVER')")
    @Operation(summary = "반려 후 기존 계정으로 재신청", description = "가입·재신청 API 합산 계정별 첫 요청부터 60초 동안 최대 5회. 업무 실패와 멱등 재전송도 집계.", operationId = "resubmitDriverRegistration")
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "기존 기사로 재신청 접수", headers = [Header(name = "Idempotency-Replayed", description = "동일 요청의 최초 성공 결과 재현 여부", schema = Schema(type = "boolean"))]),
        ApiResponse(responseCode = "429", description = "계정별 가입·재신청 합산 요청 한도 초과", headers = [Header(name = "Retry-After", description = "다시 요청하기까지 남은 초", schema = Schema(type = "integer", format = "int64", minimum = "1"))], content = [Content(schema = Schema(implementation = ErrorResponse::class))]),
    )
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
    fun detail(@Min(1) @PathVariable driverId: Long): ResponseEntity<DriverDetailResponse>

    @PostMapping("/{driverId}/approval")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "기사 가입 승인 또는 반려", operationId = "decideDriverApproval")
    @ApiResponse(responseCode = "200", description = "심사 결과 반영", headers = [Header(name = "Idempotency-Replayed", description = "동일 요청의 최초 성공 결과 재현 여부", schema = Schema(type = "boolean"))])
    fun decide(
        @Parameter(hidden = true) @AuthenticationPrincipal principal: AuthPrincipal,
        @Min(1) @PathVariable driverId: Long,
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
