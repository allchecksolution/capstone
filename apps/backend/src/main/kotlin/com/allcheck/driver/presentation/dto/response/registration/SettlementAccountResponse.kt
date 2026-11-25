package com.allcheck.driver.presentation.dto.response.registration

import io.swagger.v3.oas.annotations.media.Schema

data class SettlementAccountResponse(
    @field:Schema(description = "은행명") val bankName: String,
    @field:Schema(description = "정산계좌 번호. 관리자 조회에서는 마스킹") val accountNumber: String,
    @field:Schema(description = "예금주명") val holderName: String,
)
