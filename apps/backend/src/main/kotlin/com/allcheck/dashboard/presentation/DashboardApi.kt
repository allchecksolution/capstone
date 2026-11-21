package com.allcheck.dashboard.presentation

import com.allcheck.global.security.principal.AuthPrincipal
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.responses.ApiResponse
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
