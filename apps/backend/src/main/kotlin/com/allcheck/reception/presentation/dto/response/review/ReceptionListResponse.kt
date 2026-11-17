package com.allcheck.reception.presentation.dto.response.review

import com.allcheck.reception.domain.vo.ReceptionProcessingStatus
import com.allcheck.reception.domain.vo.review.ReceptionReviewStatus
import io.swagger.v3.oas.annotations.media.Schema
import java.time.Instant

@Schema(name = "ReceptionSummary")
data class ReceptionSummaryResponse(
    @field:Schema(description = "접수 식별자")
    val receptionId: String,
    @field:Schema(description = "Backend 접수 수신 시각")
    val receivedAt: Instant,
    @field:Schema(description = "통화 상대 번호. 마지막 네 자리 외 숫자를 마스킹한다.")
    val callerPhoneMasked: String?,
    @field:Schema(description = "관리자 검토 상태")
    val reviewStatus: ReceptionReviewStatus,
    @field:Schema(description = "녹음 원본 검증 상태")
    val recordingStatus: ReceptionProcessingStatus,
    @field:Schema(description = "전사 실행 상태")
    val sttStatus: ReceptionProcessingStatus,
    @field:Schema(description = "구조화 처리 상태. 편집 가능 여부는 reviewEditable을 사용한다.")
    val analysisStatus: ReceptionProcessingStatus,
    @field:Schema(description = "검토 요청에 사용하는 업무 버전")
    val version: Int,
)

@Schema(name = "ReceptionList")
data class ReceptionListResponse(
    @field:Schema(description = "본인이 생성한 접수 목록")
    val items: List<ReceptionSummaryResponse>,
    @field:Schema(description = "소유자 및 검토 상태 필터를 적용한 페이지 정보")
    val page: ReceptionPageInfo,
)

@Schema(name = "PageInfo")
data class ReceptionPageInfo(
    @field:Schema(description = "1부터 시작하는 페이지") val page: Int,
    @field:Schema(description = "페이지 크기") val size: Int,
    @field:Schema(description = "전체 건수") val totalElements: Long,
    @field:Schema(description = "전체 페이지 수") val totalPages: Int,
)
