package com.allcheck.reception.presentation.dto.request

import com.allcheck.reception.domain.vo.ReceptionConfirmationData
import com.allcheck.reception.domain.vo.WorkType
import com.fasterxml.jackson.annotation.JsonSetter
import com.fasterxml.jackson.annotation.Nulls
import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Pattern
import java.time.OffsetDateTime

/** 길이는 유니코드 코드 포인트로, 방문 범위와 서버 현재 시각은 검토 완료 도메인 동작에서 검증한다. */
@Schema(name = "ReceptionConfirmationFields", description = "검토 완료에 필요한 전체 필드")
data class ReceptionConfirmationFieldsRequest(
    @field:Schema(description = "작업 유형")
    val workType: WorkType,
    @field:NotBlank
    @field:Schema(description = "고객이 전달한 증상", maxLength = 2000)
    val symptoms: String,
    @field:NotBlank
    @field:Schema(description = "방문 주소", maxLength = 300)
    val address: String,
    @field:Schema(description = "정확한 방문 희망 시작 시각. 종료 시각이 미래이면 과거 시작도 허용한다.")
    val desiredVisitFrom: OffsetDateTime,
    @field:Schema(description = "시작 시각과 검토 완료 시점의 서버 현재 시각보다 이후인 방문 희망 종료 시각")
    val desiredVisitTo: OffsetDateTime,
    @field:Pattern(regexp = "[0-9+-]{9,20}")
    @field:Schema(description = "관리자가 확인한 고객 연락처. 통화 메타데이터의 전화번호와 구분한다.")
    val contactPhone: String,
    @param:JsonSetter(nulls = Nulls.FAIL)
    @field:Schema(description = "고객 요청 등 부가정보. 생략하면 최신 편집값의 메모를 비운다.", maxLength = 2000)
    val notes: String? = null,
) {
    fun toConfirmationData() = ReceptionConfirmationData(
        workType = workType,
        symptoms = symptoms,
        address = address,
        desiredVisitFrom = desiredVisitFrom,
        desiredVisitTo = desiredVisitTo,
        contactPhone = contactPhone,
        notes = notes,
    )
}
