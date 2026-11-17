package com.allcheck.reception.presentation.dto.request.review

import com.allcheck.reception.domain.vo.review.ReceptionCreationData
import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.constraints.Pattern
import java.time.OffsetDateTime

@Schema(name = "CreateReception")
data class CreateReceptionRequest(
    @field:Pattern(regexp = "[0-9+-]{9,20}")
    @field:Schema(description = "확보 가능한 통화 상대 번호. 최종 고객 연락처와 별개다.")
    val callerPhone: String? = null,
    @field:Schema(description = "확보 가능한 실제 통화 시작 시각. AI의 상대 날짜 해석 기준으로 사용하지 않는다.")
    val callStartedAt: OffsetDateTime? = null,
) {
    fun toCreationData() = ReceptionCreationData(callerPhone, callStartedAt)
}
