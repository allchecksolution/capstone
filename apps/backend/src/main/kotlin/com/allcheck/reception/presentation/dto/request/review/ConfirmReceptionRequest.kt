package com.allcheck.reception.presentation.dto.request.review

import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.Valid
import jakarta.validation.constraints.Min

@Schema(name = "ConfirmReception", description = "전체 최종값을 제출하여 검토를 완료한다.")
data class ConfirmReceptionRequest(
    @field:Min(1)
    @field:Schema(description = "마지막 조회에서 받은 접수 업무 버전")
    val expectedVersion: Int,
    @field:Valid
    @field:Schema(description = "관리자가 확인한 전체 최종값. 선택 notes를 생략하면 기존 notes를 비운다.")
    val finalData: ReceptionConfirmationFieldsRequest,
)
