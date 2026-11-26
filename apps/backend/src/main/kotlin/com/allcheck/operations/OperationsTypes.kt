package com.allcheck.operations

import com.allcheck.global.exception.BusinessException
import com.allcheck.global.exception.GlobalErrorCode
import java.time.Instant
import java.time.OffsetDateTime

enum class RequestStatus { OPEN, ACCEPTED, FOLLOW_UP_REQUIRED, COMPLETED, CANCELLED }
enum class RoundStatus { SEARCHING, OFFERING, ASSIGNED, NO_CANDIDATES, SEARCH_FAILED, DELIVERY_FAILED, NO_ACCEPT, ROUND_CLOSED }
enum class OfferStatus { PENDING, ACCEPTED, REJECTED, EXPIRED, RESPONSE_CLOSED }
enum class DeliveryStatus { PENDING, PROVIDER_ACCEPTED, FAILED }
enum class WorkStatus { NOT_STARTED, EN_ROUTE, ARRIVED, IN_PROGRESS, REVISIT_REQUIRED, WORK_DONE, CANCELLED }
enum class WorkType { BLOCKAGE, EXTERIOR_LEAK, INTERIOR_LEAK, BOILER, WATERPROOFING }
enum class PhotoPhase { BEFORE, AFTER }
enum class CancellationReason { SCHEDULE_CONFLICT, EQUIPMENT_UNAVAILABLE, TRAVEL_ISSUE, CUSTOMER_UNREACHABLE, OTHER }
enum class FollowUpChoice { REDISPATCH, CANCEL_REQUEST }
enum class ActorRole { ADMIN, DRIVER }

data class OperationsActor(val role: ActorRole, val id: Long) {
    init {
        require(id > 0)
    }
}

data class SiteCoordinates(val longitude: Double, val latitude: Double, val area: String) {
    init {
        require(longitude.isFinite() && longitude in -180.0..180.0)
        require(latitude.isFinite() && latitude in -90.0..90.0)
        require(area.isNotBlank())
    }
}

/** 접수 확정 시점의 변경 불가 업무 입력. 기사에게 제공할 때 연락처/주소의 권한을 별도로 적용한다. */
data class RequestIntake(
    val workType: WorkType,
    val symptoms: String,
    val address: String,
    val desiredVisitFrom: OffsetDateTime,
    val desiredVisitTo: OffsetDateTime,
    val contactPhone: String,
    val notes: String?,
)

data class FollowUpTarget(val kind: String, val id: Long) {
    init {
        require(kind in setOf("ROUND", "CANCELLATION") && id > 0)
    }
}

data class ReportContent(
    @field:io.swagger.v3.oas.annotations.media.Schema(description = "수행한 작업 내용") val workDescription: String,
    @field:io.swagger.v3.oas.annotations.media.Schema(description = "현장 진단") val diagnosis: String,
    @field:io.swagger.v3.oas.annotations.media.Schema(description = "원 단위 정수 총액") val totalCost: Long,
) {
    init {
        validInput(workDescription.isNotBlank() && diagnosis.isNotBlank() && totalCost >= 0)
    }
}

data class CancellationFact(val id: Long, val reasonCode: CancellationReason, val reasonDetail: String, val previousStatus: WorkStatus, val cancelledAt: Instant)
data class WorkEvent(val status: WorkStatus, val at: Instant, val reason: String? = null, val plannedRevisitAt: Instant? = null)

fun validInput(condition: Boolean) {
    if (!condition) throw BusinessException(GlobalErrorCode.INVALID_INPUT_VALUE)
}

fun expectedVersion(actual: Int, expected: Int) {
    if (actual != expected) throw BusinessException(GlobalErrorCode.VERSION_CONFLICT)
}
