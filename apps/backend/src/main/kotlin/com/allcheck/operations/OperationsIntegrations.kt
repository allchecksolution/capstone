package com.allcheck.operations

import java.time.Duration
import java.time.Instant

/** Driver 도메인의 일관된 현재 스냅샷. 페이지 누락/조회 오류를 빈 목록으로 반환하지 않는다. */
interface OperationsDrivers {
    fun candidates(context: CandidateContext): List<DriverAvailability>

    /**
     * 수락 트랜잭션에 참여하는 짧은 로컬 조회. 자격·READY·지역·GPS의 권위 있는 행을 잠그거나
     * 동등한 버전 검증으로 커밋까지 변경 경합을 보호한다. 원격 HTTP 호출을 여기서 수행하지 않는다.
     */
    fun lockForAcceptance(driverId: Long, context: CandidateContext): DriverAvailability?
}

data class CandidateContext(val workType: WorkType, val site: SiteCoordinates, val radiusKm: Int, val observedAt: Instant)
data class DriverAvailability(
    val driverId: Long,
    val activated: Boolean,
    val ready: Boolean,
    val specialties: Set<WorkType>,
    val servesSite: Boolean,
    val longitude: Double?,
    val latitude: Double?,
    val locationAt: Instant?,
    val locationValidity: Duration = Duration.ofMinutes(5),
) {
    fun eligible(context: CandidateContext): Boolean {
        val located = locationAt ?: return false
        return driverId > 0 &&
            activated &&
            ready &&
            context.workType in specialties &&
            servesSite &&
            longitude != null &&
            longitude.isFinite() &&
            longitude in -180.0..180.0 &&
            latitude != null &&
            latitude.isFinite() &&
            latitude in -90.0..90.0 &&
            located <= context.observedAt &&
            located >= context.observedAt.minus(locationValidity)
    }
    override fun toString(): String = "DriverAvailability(driverId=$driverId, activated=$activated, ready=$ready)"
}

/** 외부 조회 실패 분류. 원격 응답 본문/좌표/개인정보를 예외 메시지에 포함하지 않는다. */
class DriverLookupException(val retryable: Boolean) : RuntimeException("Driver lookup unavailable")

interface OperationsGeocoder {
    fun resolve(address: String): SiteCoordinates
}

/** Identity 도메인이 설치 소유권/권한/토큰 갱신을 책임진다. operations는 전송 이력만 소유한다. */
interface OperationsPushDevices {
    fun installations(actor: OperationsActor): List<String>
    fun administrators(): List<Long>
    fun current(actor: OperationsActor, installationId: String): PushBinding?
    fun invalidateIfCurrent(actor: OperationsActor, installationId: String, binding: PushBinding)
}

data class PushBinding(val token: String, val version: String) {
    override fun toString(): String = "PushBinding(version=$version)"
}
