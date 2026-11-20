package com.allcheck.driver.application.dispatch

import com.allcheck.driver.DispatchDriver
import com.allcheck.driver.DriverDispatchOperations
import com.allcheck.driver.DriverOperations
import com.allcheck.driver.domain.entity.dispatch.DriverDispatchState
import com.allcheck.driver.domain.entity.registration.Driver
import com.allcheck.driver.domain.vo.dispatch.DriverAvailability
import com.allcheck.driver.domain.vo.dispatch.LocationReport
import com.allcheck.driver.domain.vo.registration.WorkType
import com.allcheck.driver.exception.DriverErrorCode
import com.allcheck.driver.infrastructure.repository.dispatch.DispatchDriverRow
import com.allcheck.driver.infrastructure.repository.dispatch.DriverDispatchStateRepository
import com.allcheck.driver.infrastructure.repository.registration.DriverRepository
import com.allcheck.driver.presentation.dto.response.dispatch.AvailabilityStateResponse
import com.allcheck.driver.presentation.dto.response.dispatch.LocationStateResponse
import com.allcheck.global.exception.BusinessException
import com.allcheck.identity.IdentityAccountOperations
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.Duration
import java.time.Instant

@Service
@Transactional(readOnly = true)
class DriverDispatchStateService(
    private val identity: IdentityAccountOperations,
    private val drivers: DriverRepository,
    private val driverAccess: DriverOperations,
    private val states: DriverDispatchStateRepository,
    private val clock: Clock,
    @Value("\${driver.dispatch.location-validity:5m}") private val locationValidity: Duration,
) : DriverDispatchOperations {
    init {
        require(locationValidity.isPositive) { "Driver location validity must be positive." }
    }

    fun availability(accountId: Long): AvailabilityStateResponse {
        val driver = drivers.findByAccountId(accountId) ?: throw BusinessException(DriverErrorCode.DRIVER_NOT_ACTIVE)
        driver.requireActive()
        return state(driver).availabilityResponse(driver)
    }

    @Transactional
    fun updateAvailability(accountId: Long, availability: DriverAvailability): AvailabilityStateResponse {
        val driver = lockDriver(accountId)
        val state = state(driver)
        state.changeAvailability(availability, clock.instant())
        states.save(state)
        return state.availabilityResponse(driver)
    }

    @Transactional
    fun reportLocation(accountId: Long, report: LocationReport): LocationStateResponse {
        val driver = lockDriver(accountId)
        val now = clock.instant()
        val state = state(driver)
        state.reportLocation(report, now)
        states.save(state)
        return LocationStateResponse(state.usability(now, locationValidity), state.locationEventAt, state.locationIssue)
    }

    override fun candidates(workType: String, observedAt: Instant): List<DispatchDriver> {
        val rows = states.candidates(null, WorkType.valueOf(workType), observedAt.minus(locationValidity), observedAt)
        val active = identity.activeDriverAccountIds(rows.map { it.accountId }.distinct())
        return snapshots(rows.filter { it.accountId in active })
    }

    @Transactional(propagation = Propagation.MANDATORY)
    override fun lockCandidate(driverId: Long, workType: String): DispatchDriver? {
        driverAccess.lockActiveDriver(driverId)
        val observedAt = clock.instant()
        val rows = states.candidates(driverId, WorkType.valueOf(workType), observedAt.minus(locationValidity), observedAt)
        return snapshots(rows).singleOrNull()
    }

    private fun snapshots(rows: List<DispatchDriverRow>): List<DispatchDriver> = rows.groupBy { it.driverId }.map { (id, group) ->
        val location = group.first()
        DispatchDriver(
            id,
            group.mapTo(mutableSetOf()) { it.region },
            location.longitude,
            location.latitude,
            location.measuredAt,
            locationValidity,
        )
    }

    private fun lockDriver(accountId: Long): Driver {
        identity.lockActiveDriverAccount(accountId)
        return (drivers.findByAccountIdForUpdate(accountId) ?: throw BusinessException(DriverErrorCode.DRIVER_NOT_ACTIVE))
            .also(Driver::requireActive)
    }

    private fun state(driver: Driver): DriverDispatchState {
        val id = checkNotNull(driver.id)
        return states.findById(id).orElseGet { DriverDispatchState(id, driver.submittedAt) }
    }

    private fun DriverDispatchState.availabilityResponse(driver: Driver) = AvailabilityStateResponse(availability, driver.accountStatus, availabilityUpdatedAt)
}
