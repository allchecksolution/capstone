package com.allcheck.operations.coordination.presentation.api

import com.allcheck.global.exception.ErrorResponse
import com.allcheck.operations.RequestStatus
import com.allcheck.operations.coordination.presentation.dto.CancelWorkRequest
import com.allcheck.operations.coordination.presentation.dto.DispatchHistoryResponse
import com.allcheck.operations.coordination.presentation.dto.DispatchOfferResponse
import com.allcheck.operations.coordination.presentation.dto.FollowUpDecisionRequest
import com.allcheck.operations.coordination.presentation.dto.MonthlyStatisticsResponse
import com.allcheck.operations.coordination.presentation.dto.OperationResponse
import com.allcheck.operations.coordination.presentation.dto.OperationsPage
import com.allcheck.operations.coordination.presentation.dto.PhotoAccessResponse
import com.allcheck.operations.coordination.presentation.dto.PhotoUploadResponse
import com.allcheck.operations.coordination.presentation.dto.RegisterServiceRequest
import com.allcheck.operations.coordination.presentation.dto.RespondOfferRequest
import com.allcheck.operations.coordination.presentation.dto.ReviseReportRequest
import com.allcheck.operations.coordination.presentation.dto.RevisitPlanRequest
import com.allcheck.operations.coordination.presentation.dto.RoundHistoryResponse
import com.allcheck.operations.coordination.presentation.dto.ServiceRequestResponse
import com.allcheck.operations.coordination.presentation.dto.SubmitReportRequest
import com.allcheck.operations.coordination.presentation.dto.TransitionWorkRequest
import com.allcheck.operations.coordination.presentation.dto.WorkPhotoUploadRequest
import com.allcheck.operations.coordination.presentation.dto.WorkReportResponse
import com.allcheck.operations.coordination.presentation.dto.WorkResponse
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import io.swagger.v3.oas.annotations.security.SecurityRequirement
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.core.Authentication
import org.springframework.validation.annotation.Validated
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseStatus

@Validated
@Tag(name = "Operations", description = "의뢰·배차·작업·보고서. 변경 응답은 최초 성공 결과이며 최신 상태는 GET으로 갱신한다. 진행 화면 초기 폴링 5초.")
@SecurityRequirement(name = "bearerAuth")
@RequestMapping("/api/v1", produces = ["application/json"])
@ApiResponses(
    ApiResponse(responseCode = "400", description = "입력 형식·범위 검증 실패", content = [Content(schema = Schema(implementation = ErrorResponse::class))]),
    ApiResponse(responseCode = "401", description = "인증 필요"),
    ApiResponse(responseCode = "403", description = "현재 역할 또는 권한 부족"),
    ApiResponse(responseCode = "404", description = "자원 없음 또는 자원 접근 범위 밖"),
    ApiResponse(responseCode = "409", description = "상태·버전·만료·중복 키 또는 진행 중 명령 충돌"),
    ApiResponse(responseCode = "503", description = "필요한 외부 연동 미준비"),
)
interface OperationsApi {
    @GetMapping("/dispatch-cancellations")
    @Operation(operationId = "listDispatchCancellations", summary = "ADMIN: 작업 취소 이력 목록. 상세 사유 포함")
    fun cancellations(@Parameter(hidden = true) authentication: Authentication, @RequestParam(defaultValue = "1") @Min(1) @Max(1000000) page: Int, @RequestParam(defaultValue = "20") @Min(1) @Max(100) size: Int): ResponseEntity<OperationsPage<WorkResponse>>

    @GetMapping("/dispatch-cancellations/{cancellationId}")
    @Operation(operationId = "getDispatchCancellation", summary = "ADMIN: 특정 취소의 의뢰 배차 전체 이력과 현재 후속 조치")
    fun cancellation(@Parameter(hidden = true) authentication: Authentication, @PathVariable cancellationId: String): ResponseEntity<DispatchHistoryResponse>

    @GetMapping("/reports")
    @Operation(operationId = "listWorkReports", summary = "ADMIN: 제출한 보고서 목록")
    fun reports(@Parameter(hidden = true) authentication: Authentication, @RequestParam(defaultValue = "1") @Min(1) @Max(1000000) page: Int, @RequestParam(defaultValue = "20") @Min(1) @Max(100) size: Int): ResponseEntity<OperationsPage<WorkReportResponse>>

    @PostMapping("/service-requests", consumes = ["application/json"])
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(operationId = "registerServiceRequest", summary = "ADMIN: 확정 접수 등록 및 자동 배차 시작", description = "원본 접수 소유자만 등록한다. SGIS 단일 현장 좌표를 먼저 확보하고 의뢰·회차·검색 의도·접수 연결을 원자적으로 저장한다.")
    @ApiResponse(responseCode = "422", description = "현장 주소 불명확. 접수 확정은 보존된다.")
    fun register(@Parameter(hidden = true) authentication: Authentication, @RequestHeader("Idempotency-Key") @NotBlank @Size(max = 200) key: String, @Valid @RequestBody body: RegisterServiceRequest): ResponseEntity<OperationResponse>

    @GetMapping("/service-requests")
    @Operation(operationId = "listServiceRequests", summary = "ADMIN: 모든 관리자 공유 의뢰 목록")
    fun requests(@Parameter(hidden = true) authentication: Authentication, @RequestParam(required = false) status: RequestStatus?, @RequestParam(defaultValue = "1") @Min(1) @Max(1000000) page: Int, @RequestParam(defaultValue = "20") @Min(1) @Max(100) size: Int): ResponseEntity<OperationsPage<ServiceRequestResponse>>

    @GetMapping("/service-requests/{requestId}")
    @Operation(operationId = "getServiceRequest", summary = "ADMIN: 의뢰 현재 상태")
    fun request(@Parameter(hidden = true) authentication: Authentication, @PathVariable requestId: String): ResponseEntity<ServiceRequestResponse>

    @GetMapping("/service-requests/{requestId}/dispatch-history")
    @Operation(operationId = "getDispatchHistory", summary = "ADMIN: 취소 사유를 포함한 전체 배차 및 재배차 이력")
    fun history(@Parameter(hidden = true) authentication: Authentication, @PathVariable requestId: String): ResponseEntity<DispatchHistoryResponse>

    @GetMapping("/service-requests/{requestId}/dispatch-rounds")
    @Operation(operationId = "getDispatchRounds", summary = "ADMIN: 모든 배차 회차 결과")
    fun rounds(@Parameter(hidden = true) authentication: Authentication, @PathVariable requestId: String): ResponseEntity<List<RoundHistoryResponse>>

    @PostMapping("/service-requests/{requestId}/follow-up-decisions", consumes = ["application/json"])
    @Operation(operationId = "decideOperationsFollowUp", summary = "ADMIN: 현재 후속 대상의 재배차 또는 의뢰 취소 결정")
    fun decide(@Parameter(hidden = true) authentication: Authentication, @PathVariable requestId: String, @RequestHeader("Idempotency-Key") @NotBlank @Size(max = 200) key: String, @Valid @RequestBody body: FollowUpDecisionRequest): ResponseEntity<OperationResponse>

    @GetMapping("/drivers/me/dispatch-offers")
    @Operation(operationId = "listMyDispatchOffers", summary = "DRIVER: 본인 제안 목록. 주소는 읍면동까지만 공개")
    fun offers(@Parameter(hidden = true) authentication: Authentication, @RequestParam(defaultValue = "1") @Min(1) @Max(1000000) page: Int, @RequestParam(defaultValue = "20") @Min(1) @Max(100) size: Int): ResponseEntity<OperationsPage<DispatchOfferResponse>>

    @GetMapping("/dispatch-offers/{offerId}")
    @Operation(operationId = "getMyDispatchOffer", summary = "DRIVER: 본인 논리적 제안 상세. 기기별 제안이 아니다.")
    fun offer(@Parameter(hidden = true) authentication: Authentication, @PathVariable offerId: String): ResponseEntity<DispatchOfferResponse>

    @PostMapping("/dispatch-offers/{offerId}/response", consumes = ["application/json"])
    @Operation(operationId = "respondDispatchOffer", summary = "DRIVER: 제안 수락 또는 거절", description = "수락은 현재 위치·자격·점유·과거 동일 의뢰 취소를 재검증한다. 같은 의뢰의 다중 수락과 기사별 다중 점유를 DB에서 보호한다. 만료 시각 이상은 거부한다.")
    fun respond(@Parameter(hidden = true) authentication: Authentication, @PathVariable offerId: String, @RequestHeader("Idempotency-Key") @NotBlank @Size(max = 200) key: String, @Valid @RequestBody body: RespondOfferRequest): ResponseEntity<OperationResponse>

    @PostMapping("/dispatch-offers/{offerId}/received")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(operationId = "recordDispatchOfferReceived", summary = "DRIVER: 실제 수신 신호. 공급자 성공과 별개")
    fun received(@Parameter(hidden = true) authentication: Authentication, @PathVariable offerId: String): ResponseEntity<Void>

    @PostMapping("/dispatch-offers/{offerId}/viewed")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(operationId = "recordDispatchOfferViewed", summary = "DRIVER: 실제 열람 신호")
    fun viewed(@Parameter(hidden = true) authentication: Authentication, @PathVariable offerId: String): ResponseEntity<Void>

    @GetMapping("/drivers/me/works")
    @Operation(operationId = "listMyWorks", summary = "DRIVER: 현재 작업 또는 본인 과거 이력", description = "HISTORY의 month는 Asia/Seoul 실제 완료·취소 시각 기준. CURRENT에 month를 지정하면 400. 과거 작업 연락처는 null.")
    fun works(@Parameter(hidden = true) authentication: Authentication, @RequestParam(defaultValue = "CURRENT") scope: String, @RequestParam(required = false) month: String?, @RequestParam(defaultValue = "1") @Min(1) @Max(1000000) page: Int, @RequestParam(defaultValue = "20") @Min(1) @Max(100) size: Int): ResponseEntity<OperationsPage<WorkResponse>>

    @GetMapping("/drivers/me/work-statistics")
    @Operation(operationId = "getMyWorkStatistics", summary = "DRIVER: 월별 배정·완료·작업 취소와 다섯 유형 완료 분포")
    fun statistics(@Parameter(hidden = true) authentication: Authentication, @RequestParam month: String): ResponseEntity<MonthlyStatisticsResponse>

    @GetMapping("/works/{workId}")
    @Operation(operationId = "getWork", summary = "ADMIN 또는 권한 있는 DRIVER: 작업과 재방문 이력", description = "담당자는 본인 과거 작업을 읽을 수 있다. 현재 담당자는 이전 연결 작업을 인계 조회할 수 있다. 이전 기사는 이후 타인 작업을 볼 수 없다.")
    fun work(@Parameter(hidden = true) authentication: Authentication, @PathVariable workId: String): ResponseEntity<WorkResponse>

    @PostMapping("/works/{workId}/transitions", consumes = ["application/json"])
    @Operation(operationId = "transitionWork", summary = "DRIVER: 출발·도착·작업·재방문 상태 변경")
    fun transition(@Parameter(hidden = true) authentication: Authentication, @PathVariable workId: String, @RequestHeader("Idempotency-Key") @NotBlank @Size(max = 200) key: String, @Valid @RequestBody body: TransitionWorkRequest): ResponseEntity<OperationResponse>

    @PostMapping("/works/{workId}/revisit-plan", consumes = ["application/json"])
    @Operation(operationId = "updateRevisitPlan", summary = "DRIVER: 재방문 대기 중 일정 변경. 이전 이력 보존")
    fun revisit(@Parameter(hidden = true) authentication: Authentication, @PathVariable workId: String, @RequestHeader("Idempotency-Key") @NotBlank @Size(max = 200) key: String, @Valid @RequestBody body: RevisitPlanRequest): ResponseEntity<OperationResponse>

    @PostMapping("/works/{workId}/cancellations", consumes = ["application/json"])
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(operationId = "cancelWork", summary = "DRIVER: 즉시 작업 취소와 관리자 후속 조치 전환", description = "다섯 사유 모두 상세 내용 필수. 관리자 승인 없이 기사 점유를 해제한다.")
    fun cancel(@Parameter(hidden = true) authentication: Authentication, @PathVariable workId: String, @RequestHeader("Idempotency-Key") @NotBlank @Size(max = 200) key: String, @Valid @RequestBody body: CancelWorkRequest): ResponseEntity<OperationResponse>

    @PostMapping("/works/{workId}/reports", consumes = ["application/json"])
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(operationId = "submitWorkReport", summary = "DRIVER: 최종 보고서 최초 제출과 작업·의뢰 완료", description = "IN_PROGRESS 담당자만 가능. BEFORE와 AFTER 각 3~5장. 임시 저장 및 DURING 사진 필드 없음.")
    fun submit(@Parameter(hidden = true) authentication: Authentication, @PathVariable workId: String, @RequestHeader("Idempotency-Key") @NotBlank @Size(max = 200) key: String, @Valid @RequestBody body: SubmitReportRequest): ResponseEntity<OperationResponse>

    @GetMapping("/reports/{reportId}")
    @Operation(operationId = "getWorkReport", summary = "ADMIN 또는 권한 있는 DRIVER: 원본·최신 보고서와 수정 이력")
    fun report(@Parameter(hidden = true) authentication: Authentication, @PathVariable reportId: String): ResponseEntity<WorkReportResponse>

    @PostMapping("/reports/{reportId}/revisions", consumes = ["application/json"])
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(operationId = "reviseWorkReport", summary = "ADMIN: 사유를 첨부해 진단·작업 내용·총액 수정. 사진과 원본은 보존")
    fun revise(@Parameter(hidden = true) authentication: Authentication, @PathVariable reportId: String, @RequestHeader("Idempotency-Key") @NotBlank @Size(max = 200) key: String, @Valid @RequestBody body: ReviseReportRequest): ResponseEntity<OperationResponse>

    @PostMapping("/works/{workId}/photos/upload-requests", consumes = ["application/json"])
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(operationId = "reserveWorkPhotoUpload", summary = "DRIVER: 비공개 사진 직접 업로드 예약", description = "JPEG/PNG 1~10,000,000바이트. 동일 키에는 예약을 재사용하고 URL을 새로 발급한다.")
    fun reservePhoto(@Parameter(hidden = true) authentication: Authentication, @PathVariable workId: String, @RequestHeader("Idempotency-Key") @NotBlank @Size(max = 200) key: String, @Valid @RequestBody body: WorkPhotoUploadRequest): ResponseEntity<PhotoUploadResponse>

    @PostMapping("/works/{workId}/photos/{photoId}/upload-requests")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(operationId = "renewWorkPhotoUpload", summary = "DRIVER: 미완료 사진의 새 업로드 URL. 예약과 기한 외 업무 상태는 변경하지 않는다.")
    fun renewPhoto(@Parameter(hidden = true) authentication: Authentication, @PathVariable workId: String, @PathVariable photoId: String): ResponseEntity<PhotoUploadResponse>

    @PostMapping("/works/{workId}/photos/{photoId}/completion")
    @Operation(operationId = "completeWorkPhotoUpload", summary = "DRIVER: S3 실제 MIME·크기·체크섬·이미지 시그니처 검증")
    fun completePhoto(@Parameter(hidden = true) authentication: Authentication, @PathVariable workId: String, @PathVariable photoId: String, @RequestHeader("Idempotency-Key") @NotBlank @Size(max = 200) key: String): ResponseEntity<OperationResponse>

    @GetMapping("/works/{workId}/photos/{photoId}/access")
    @Operation(operationId = "getWorkPhotoAccess", summary = "ADMIN 또는 권한 있는 DRIVER: 검증된 사진 단기 읽기 URL")
    fun photoAccess(@Parameter(hidden = true) authentication: Authentication, @PathVariable workId: String, @PathVariable photoId: String): ResponseEntity<PhotoAccessResponse>
}
