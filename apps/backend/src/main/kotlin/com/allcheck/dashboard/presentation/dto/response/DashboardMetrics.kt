package com.allcheck.dashboard.presentation.dto.response

import io.swagger.v3.oas.annotations.media.Schema
import java.math.BigDecimal

data class DashboardMetrics(
    @field:Schema(description = "필터와 일치하는 의뢰 수")
    val totalRequests: Long,
    @field:Schema(description = "현재 OPEN, ACCEPTED 또는 FOLLOW_UP_REQUIRED인 의뢰 수")
    val openRequests: Long,
    @field:Schema(description = "현재 완료된 의뢰 수")
    val completedRequests: Long,
    @field:Schema(description = "현재 최종 취소된 의뢰 수")
    val cancelledRequests: Long,
    @field:Schema(description = "전체 의뢰 중 완료 의뢰의 비율", minimum = "0", maximum = "1")
    val completionRate: Double,
    @field:Schema(description = "전체 의뢰 중 최종 취소 의뢰의 비율", minimum = "0", maximum = "1")
    val cancellationRate: Double,
    @field:Schema(description = "완료 의뢰의 최신 유효 보고서 비용 합계(원)")
    val totalCostKrw: BigDecimal,
    @field:Schema(description = "완료 보고서 기준 평균 비용(원). 일치하는 완료 보고서가 없으면 null.")
    val averageCostKrw: BigDecimal?,
    @field:Schema(description = "조회 시작일부터 7일씩 나눈 의뢰 건수. 데이터가 있는 구간을 반환한다.")
    val weeklyCounts: List<WeeklyRequestCount>,
    @field:Schema(description = "전체 일치 의뢰의 5개 작업 유형별 분포")
    val workTypes: List<RequestTypeCount>,
)
