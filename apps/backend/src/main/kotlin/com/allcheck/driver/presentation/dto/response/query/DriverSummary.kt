package com.allcheck.driver.presentation.dto.response.query

import com.allcheck.driver.domain.vo.registration.DriverAccountStatus
import io.swagger.v3.oas.annotations.media.Schema
import java.time.Instant

data class DriverSummary(
    @field:Schema(description = "기사 식별자") val driverId: Long,
    @field:Schema(description = "근로자명") val workerName: String,
    @field:Schema(description = "사업체명") val businessName: String,
    @field:Schema(description = "가입 상태") val accountStatus: DriverAccountStatus,
    @field:Schema(description = "현재 신청 제출 시각") val submittedAt: Instant,
    @field:Schema(description = "현재 버전") val version: Long,
)
