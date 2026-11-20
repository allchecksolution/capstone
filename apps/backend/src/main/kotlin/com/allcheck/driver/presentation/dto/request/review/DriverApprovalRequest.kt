package com.allcheck.driver.presentation.dto.request.review

import com.allcheck.driver.domain.vo.review.ApprovalDecision
import com.allcheck.driver.presentation.dto.request.StrictDriverRequest
import com.fasterxml.jackson.annotation.JsonIgnore
import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.constraints.AssertTrue
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.Size

@Schema(additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
data class DriverApprovalRequest(
    @field:Min(1) @field:Schema(description = "조회한 현재 버전") val expectedVersion: Long,
    @field:Schema(description = "심사 결정") val decision: ApprovalDecision,
    @field:Size(min = 1, max = 1000) @field:Schema(description = "반려 시 필수 사유") val rejectionReason: String? = null,
) : StrictDriverRequest() {
    @AssertTrue(message = "반려 시 사유를 입력해야 합니다.")
    @JsonIgnore
    fun isRejectionReasonPresent(): Boolean = decision != ApprovalDecision.REJECT || !rejectionReason.isNullOrBlank()
}
