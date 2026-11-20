package com.allcheck.operations

import java.time.Duration
import java.time.Instant

/** 활성 계정, 대기 상태, 작업 유형, 서비스 지역을 충족한 기사만 반환한다. */
interface OperationsDrivers {
    /** 후보가 없으면 빈 목록을 반환하고, 조회 실패는 예외로 알린다. */
    fun candidates(context: CandidateContext): List<DriverCandidate>

    /**
     * 수락 트랜잭션에 참여하는 짧은 로컬 조회. 자격·READY·지역·GPS의 권위 있는 행을 잠그거나
     * 동등한 버전 검증으로 커밋까지 변경 경합을 보호한다. 원격 HTTP 호출을 여기서 수행하지 않는다.
     */
    fun lockForAcceptance(driverId: Long, context: CandidateContext): DriverCandidate?
}

data class CandidateContext(val workType: WorkType, val site: SiteCoordinates, val radiusKm: Int, val observedAt: Instant)
data class DriverCandidate(
    val driverId: Long,
    val longitude: Double,
    val latitude: Double,
    val locationAt: Instant,
    val locationValidity: Duration,
) {
    fun hasValidLocationAt(observedAt: Instant): Boolean = driverId > 0 &&
        longitude in -180.0..180.0 &&
        latitude in -90.0..90.0 &&
        locationAt <= observedAt &&
        locationAt >= observedAt.minus(locationValidity)

    override fun toString(): String = "DriverCandidate(driverId=$driverId)"
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
