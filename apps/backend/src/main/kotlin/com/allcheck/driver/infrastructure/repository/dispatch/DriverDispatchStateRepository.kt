package com.allcheck.driver.infrastructure.repository.dispatch

import com.allcheck.driver.domain.entity.dispatch.DriverDispatchState
import com.allcheck.driver.domain.vo.registration.WorkType
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import java.time.Instant

interface DriverDispatchStateRepository : JpaRepository<DriverDispatchState, Long> {
    @Query(
        """
        select d.id as driverId, d.accountId as accountId, region as region, state.longitude as longitude,
            state.latitude as latitude, state.locationEventAt as measuredAt
        from DriverDispatchState state join Driver d on d.id = state.driverId
        join d.profile.activityRegions region
        where (:driverId is null or d.id = :driverId)
            and d.accountStatus = com.allcheck.driver.domain.vo.registration.DriverAccountStatus.ACTIVATED
            and state.availability = com.allcheck.driver.domain.vo.dispatch.DriverAvailability.READY
            and :workType member of d.profile.specialties
            and state.longitude is not null and state.latitude is not null
            and state.locationEventAt between :validSince and :observedAt
        """,
    )
    fun candidates(driverId: Long?, workType: WorkType, validSince: Instant, observedAt: Instant): List<DispatchDriverRow>
}

interface DispatchDriverRow {
    val driverId: Long
    val accountId: Long
    val region: String
    val longitude: Double
    val latitude: Double
    val measuredAt: Instant
}
