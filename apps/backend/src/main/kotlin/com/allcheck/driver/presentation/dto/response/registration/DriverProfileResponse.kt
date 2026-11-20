package com.allcheck.driver.presentation.dto.response.registration

import com.allcheck.driver.domain.vo.registration.WorkType
import io.swagger.v3.oas.annotations.media.Schema

data class DriverProfileResponse(
    @field:Schema(description = "근로자명") val workerName: String,
    @field:Schema(description = "전화번호") val phone: String,
    @field:Schema(description = "이메일") val email: String,
    @field:Schema(description = "거주지") val residence: String,
    @field:Schema(description = "차량번호") val vehicleNumber: String,
    @field:Schema(description = "개인 정산계좌") val settlementAccount: SettlementAccountResponse,
    @field:Schema(description = "세금계산서 이메일") val taxInvoiceEmail: String,
    @field:Schema(description = "전문 분야") val specialties: List<WorkType>,
    @field:Schema(description = "활동 지역") val activityRegions: List<String>,
    @field:Schema(description = "보유 장비") val equipment: List<String>,
)
