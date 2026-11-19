package com.allcheck.operations.coordination.presentation.dto

import com.allcheck.operations.CancellationReason
import com.allcheck.operations.FollowUpChoice
import com.allcheck.operations.OfferStatus
import com.allcheck.operations.PhotoPhase
import com.allcheck.operations.ReportContent
import com.allcheck.operations.RequestStatus
import com.allcheck.operations.RoundStatus
import com.allcheck.operations.WorkStatus
import com.allcheck.operations.WorkType
import io.swagger.v3.oas.annotations.media.Schema
import java.time.Instant
import java.time.OffsetDateTime

data class OperationsPage<T>(
    @field:Schema(description = "현재 페이지 항목") val items: List<T>,
    @field:Schema(description = "1부터 시작하는 페이지") val page: Int,
    @field:Schema(description = "요청한 페이지 크기") val size: Int,
    @field:Schema(description = "다음 페이지 존재 여부") val hasNext: Boolean,
)
data class FollowUpTargetResponse(
    @field:Schema(description = "작업 취소 대상. 회차 대상이면 null") val cancellationId: String?,
    @field:Schema(description = "배차 회차 대상. 작업 취소 대상이면 null") val dispatchRoundId: String?,
)
data class ServiceRequestResponse(
    @field:Schema(description = "의뢰 식별자") val requestId: String,
    @field:Schema(description = "원본 접수 식별자. 원본 열람은 접수 소유 권한이 별도 필요") val receptionId: String,
    @field:Schema(description = "의뢰 상태") val requestStatus: RequestStatus,
    @field:Schema(description = "업무 버전") val version: Int,
    @field:Schema(description = "작업 유형") val workType: WorkType,
    @field:Schema(description = "확정 증상") val symptoms: String,
    @field:Schema(description = "확정 전체 현장 주소") val address: String,
    @field:Schema(description = "확정 방문 시작") val desiredVisitFrom: OffsetDateTime,
    @field:Schema(description = "확정 방문 종료") val desiredVisitTo: OffsetDateTime,
    @field:Schema(description = "관리자용 고객 연락처") val contactPhone: String,
    @field:Schema(description = "확정 특이사항") val notes: String?,
    @field:Schema(description = "현 회차") val currentDispatchRoundId: String?,
    @field:Schema(description = "현재 또는 최종 작업") val currentWorkId: String?,
    @field:Schema(description = "현재 미해결 후속 조치 대상") val followUpTarget: FollowUpTargetResponse?,
    @field:Schema(description = "현재 후속 조치 원인") val followUpReason: String?,
    @field:Schema(description = "의뢰 등록 시각") val createdAt: Instant,
)
data class DispatchOfferResponse(
    @field:Schema(description = "기사별 논리적 제안 식별자") val offerId: String,
    @field:Schema(description = "의뢰 식별자") val requestId: String,
    @field:Schema(description = "회차 식별자") val dispatchRoundId: String,
    @field:Schema(description = "응답 상태") val offerStatus: OfferStatus,
    @field:Schema(description = "FCM 수락과 실제 수신을 구분하는 전송 집계") val deliveryStatus: String,
    @field:Schema(description = "작업 유형") val workType: WorkType,
    @field:Schema(description = "확정 증상") val symptoms: String,
    @field:Schema(description = "시군구와 읍면동까지만 공개한 지역") val addressArea: String,
    @field:Schema(description = "후보 선정 시 직선거리 미터") val distanceMeters: Double,
    @field:Schema(description = "확정 방문 시작") val desiredVisitFrom: OffsetDateTime,
    @field:Schema(description = "확정 방문 종료") val desiredVisitTo: OffsetDateTime,
    @field:Schema(description = "특이사항") val notes: String?,
    @field:Schema(description = "제안 생성 시각") val createdAt: Instant,
    @field:Schema(description = "연장되지 않는 응답 만료 시각") val expiresAt: Instant,
    @field:Schema(description = "서버 기준 현재 응답 가능 여부") val canRespond: Boolean,
    @field:Schema(description = "최초 실제 수신 신호") val receivedAt: Instant?,
    @field:Schema(description = "최초 실제 열람 신호") val viewedAt: Instant?,
)
data class CancellationResponse(
    @field:Schema(description = "취소 식별자") val cancellationId: String,
    @field:Schema(description = "작업 식별자") val workId: String,
    @field:Schema(description = "의뢰 식별자") val requestId: String,
    @field:Schema(description = "사유 분류") val reasonCode: CancellationReason,
    @field:Schema(description = "필수 상세 사유") val reasonDetail: String,
    @field:Schema(description = "취소 직전 작업 상태") val workStatusBeforeCancellation: WorkStatus,
    @field:Schema(description = "실제 취소 시각") val cancelledAt: Instant,
)
data class WorkEventResponse(
    @field:Schema(description = "기록된 상태") val status: WorkStatus,
    @field:Schema(description = "기록 시각") val at: Instant,
    @field:Schema(description = "재방문 사유") val reason: String?,
    @field:Schema(description = "예정 재방문 시각. 미정이면 null") val plannedRevisitAt: Instant?,
)
data class WorkResponse(
    @field:Schema(description = "작업 식별자") val workId: String,
    @field:Schema(description = "의뢰 식별자") val requestId: String,
    @field:Schema(description = "원본 담당 기사 식별자") val driverId: String,
    @field:Schema(description = "이전 취소 작업 식별자") val previousWorkId: String?,
    @field:Schema(description = "진행 상태") val workStatus: WorkStatus,
    @field:Schema(description = "작업 업무 버전") val version: Int,
    @field:Schema(description = "작업 유형") val workType: WorkType,
    @field:Schema(description = "확정 증상") val symptoms: String,
    @field:Schema(description = "현장 주소") val address: String,
    @field:Schema(description = "현재 미완료 작업의 담당 기사 및 관리자에게만 공개하는 연락처") val contactPhone: String?,
    @field:Schema(description = "확정 방문 시작") val desiredVisitFrom: OffsetDateTime,
    @field:Schema(description = "확정 방문 종료") val desiredVisitTo: OffsetDateTime,
    @field:Schema(description = "확정 특이사항") val notes: String?,
    @field:Schema(description = "배정 시각") val assignedAt: Instant,
    @field:Schema(description = "첫 보고서 저장 시각") val completedAt: Instant?,
    @field:Schema(description = "실제 취소 시각") val cancelledAt: Instant?,
    @field:Schema(description = "보고서 식별자") val reportId: String?,
    @field:Schema(description = "취소 상세. 별도 조회 없이 사유 표시 가능") val cancellation: CancellationResponse?,
    @field:Schema(description = "진행과 재방문 전체 이력") val events: List<WorkEventResponse>,
    @field:Schema(description = "현재 호출자의 진행 명령 권한") val canTransition: Boolean,
    @field:Schema(description = "현재 호출자의 최초 보고서 제출 가능 여부") val canSubmitReport: Boolean,
)
data class DecisionResponse(
    @field:Schema(description = "결정 식별자") val decisionId: String,
    @field:Schema(description = "결정한 관리자") val adminId: String,
    @field:Schema(description = "결정 대상") val target: FollowUpTargetResponse,
    @field:Schema(description = "결정 내용") val decision: FollowUpChoice,
    @field:Schema(description = "새 회차. 의뢰 취소이면 null") val dispatchRoundId: String?,
    @field:Schema(description = "결정 시각") val decidedAt: Instant,
)
data class RoundHistoryResponse(
    @field:Schema(description = "회차 식별자") val dispatchRoundId: String,
    @field:Schema(description = "회차 최종 또는 현재 상태") val status: RoundStatus,
    @field:Schema(description = "검색 반경 킬로미터") val radiusKm: Int,
    @field:Schema(description = "회차 시작") val createdAt: Instant,
    @field:Schema(description = "회차 종료") val closedAt: Instant?,
    @field:Schema(description = "기사별 제안과 응답") val offers: List<OfferHistoryResponse>,
    @field:Schema(description = "배정 작업 및 취소 상세") val work: WorkResponse?,
)
data class OfferHistoryResponse(
    @field:Schema(description = "제안 식별자") val offerId: String,
    @field:Schema(description = "기사 식별자") val driverId: String,
    @field:Schema(description = "응답 상태") val status: OfferStatus,
    @field:Schema(description = "전송 집계") val deliveryStatus: String,
    @field:Schema(description = "응답 시각") val respondedAt: Instant?,
    @field:Schema(description = "고정 만료 시각") val expiresAt: Instant,
)
data class DispatchHistoryResponse(
    @field:Schema(description = "의뢰 현재 상세") val request: ServiceRequestResponse,
    @field:Schema(description = "최초 배차부터 모든 재배차 회차") val rounds: List<RoundHistoryResponse>,
    @field:Schema(description = "모든 후속 결정 이력") val followUpDecisions: List<DecisionResponse>,
)
data class ReportRevisionResponse(
    @field:Schema(description = "수정 식별자") val revisionId: String,
    @field:Schema(description = "수정 후 보고서 버전") val version: Int,
    @field:Schema(description = "수정 관리자") val adminId: String,
    @field:Schema(description = "필수 수정 사유") val reason: String,
    @field:Schema(description = "수정 전 내용") val before: ReportContent,
    @field:Schema(description = "수정 후 내용") val after: ReportContent,
    @field:Schema(description = "수정 시각") val revisedAt: Instant,
)
data class WorkReportResponse(
    @field:Schema(description = "보고서 식별자") val reportId: String,
    @field:Schema(description = "작업 식별자") val workId: String,
    @field:Schema(description = "최초 제출 내용") val original: ReportContent,
    @field:Schema(description = "최신 유효 내용") val latest: ReportContent,
    @field:Schema(description = "보존된 작업 전 사진") val beforePhotoIds: List<String>,
    @field:Schema(description = "보존된 작업 후 사진") val afterPhotoIds: List<String>,
    @field:Schema(description = "현재 보고서 버전") val version: Int,
    @field:Schema(description = "불변 최초 제출 시각") val submittedAt: Instant,
    @field:Schema(description = "관리자 수정 이력") val revisions: List<ReportRevisionResponse>,
)
data class WorkTypeDistribution(
    @field:Schema(description = "작업 유형") val workType: WorkType,
    @field:Schema(description = "해당 월 완료 작업 수") val count: Long,
    @field:Schema(description = "해당 월 완료 합계 대비 비율. 완료가 없으면 0") val ratio: Double,
)
data class MonthlyStatisticsResponse(
    @field:Schema(description = "Asia/Seoul 기준 YYYY-MM") val month: String,
    @field:Schema(description = "배정 시각 기준 배정 수") val assignments: Long,
    @field:Schema(description = "최초 보고서 저장 시각 기준 완료 수") val completed: Long,
    @field:Schema(description = "작업 취소 시각 기준 취소 수") val cancelled: Long,
    @field:Schema(description = "다섯 작업 유형별 완료 분포") val workTypes: List<WorkTypeDistribution>,
)
data class OperationResponse(
    @field:Schema(description = "이번 명령으로 생성 또는 변경한 자원 식별자") val resourceId: String,
    @field:Schema(description = "관련 의뢰 식별자") val requestId: String,
    @field:Schema(description = "이번 명령의 결과 상태. 최신 상태는 GET으로 확인") val status: String,
    @field:Schema(description = "변경한 자원의 업무 버전. 사진은 관련 작업 버전") val version: Int,
    @field:Schema(description = "명령 시각. 사진 완료에서는 최초 업로드 완료 시각") val at: Instant,
    @field:Schema(description = "생성된 배차 회차 또는 관련 작업/제안 식별자") val relatedResourceId: String?,
)
data class PhotoUploadResponse(
    @field:Schema(description = "사진 식별자") val photoFileId: String,
    @field:Schema(description = "연결 작업") val workId: String,
    @field:Schema(description = "사진 촬영 단계") val phase: PhotoPhase,
    @field:Schema(description = "직접 업로드 URL. 저장하거나 로그에 남기지 않는다") val uploadUrl: String,
    @field:Schema(description = "서명 만료") val expiresAt: Instant,
    @field:Schema(description = "업로드 메서드") val method: String,
    @field:Schema(description = "변경 없이 전송해야 하는 서명 헤더") val requiredHeaders: Map<String, List<String>>,
)
data class PhotoAccessResponse(
    @field:Schema(description = "사진 식별자") val photoFileId: String,
    @field:Schema(description = "단기 읽기 URL") val downloadUrl: String,
    @field:Schema(description = "서명 만료") val expiresAt: Instant,
    @field:Schema(description = "읽기 메서드") val method: String,
    @field:Schema(description = "검증된 이미지 형식") val contentType: String,
)
