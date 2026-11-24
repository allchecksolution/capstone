package com.allcheck.dashboard.presentation.api

import com.allcheck.dashboard.presentation.dto.request.DashboardParameters
import com.allcheck.dashboard.presentation.dto.response.DashboardPage
import com.allcheck.dashboard.presentation.dto.response.DashboardStatistics
import com.allcheck.global.exception.ErrorResponse
import com.allcheck.global.security.principal.AuthPrincipal
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import io.swagger.v3.oas.annotations.security.SecurityRequirement
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import org.springdoc.core.annotations.ParameterObject
import org.springframework.format.annotation.DateTimeFormat
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.ModelAttribute
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import java.time.LocalDate

@RequestMapping("/api/v1", produces = ["application/json"])
@Tag(name = "Dashboard", description = "관리자 의뢰 현황과 기간 통계")
@SecurityRequirement(name = "bearerAuth")
@ApiResponses(
    ApiResponse(responseCode = "400", description = "조회 기간·필터·페이지 입력이 올바르지 않음", content = [Content(schema = Schema(implementation = ErrorResponse::class))]),
    ApiResponse(responseCode = "401", description = "유효한 인증 세션 필요", content = [Content(schema = Schema(implementation = ErrorResponse::class))]),
    ApiResponse(responseCode = "403", description = "관리자 권한 필요", content = [Content(schema = Schema(implementation = ErrorResponse::class))]),
    ApiResponse(responseCode = "500", description = "서버에서 조회를 완료하지 못함", content = [Content(schema = Schema(implementation = ErrorResponse::class))]),
)
interface DashboardApi {

    @GetMapping("/service-requests")
    @Operation(operationId = "listServiceRequests", summary = "ADMIN: 의뢰 현황 및 이력 목록", description = "한국시간 의뢰 등록일과 검색·상태·유형·기사·지역을 조합한다. 기사와 작업 상태는 동일한 현재 또는 과거 작업에서 확인한다.")
    @ApiResponse(responseCode = "200", description = "의뢰당 한 행. 일치 의뢰가 없으면 items가 빈 배열이다.")
    fun requests(
        @Parameter(hidden = true) @AuthenticationPrincipal principal: AuthPrincipal?,
        @Valid @ModelAttribute @ParameterObject filter: DashboardParameters,
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) from: LocalDate?,
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) to: LocalDate?,
        @RequestParam(defaultValue = "1") @Min(1) @Max(1_000_000) page: Int,
        @RequestParam(defaultValue = "20") @Min(1) @Max(100) size: Int,
    ): ResponseEntity<DashboardPage>

    @GetMapping("/work-statistics")
    @Operation(operationId = "getWorkStatistics", summary = "ADMIN: 조회 기간 통계", description = "한국시간 기준 양 끝 날짜를 포함한다. from과 to가 필요하며, 목록과 동일한 선택 필터를 적용한다. 주별 집계는 from부터 7일 단위다.")
    @ApiResponse(responseCode = "200", description = "일치 의뢰가 없으면 hasData=false, metrics=null.")
    fun statistics(
        @Parameter(hidden = true) @AuthenticationPrincipal principal: AuthPrincipal?,
        @Valid @ModelAttribute @ParameterObject filter: DashboardParameters,
        @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) from: LocalDate,
        @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) to: LocalDate,
    ): ResponseEntity<DashboardStatistics>
}
