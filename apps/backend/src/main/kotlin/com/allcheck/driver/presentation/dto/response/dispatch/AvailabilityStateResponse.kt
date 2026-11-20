package com.allcheck.driver.presentation.dto.response.dispatch

import com.allcheck.driver.domain.vo.dispatch.DriverAvailability
import com.allcheck.driver.domain.vo.registration.DriverAccountStatus
import java.time.Instant

data class AvailabilityStateResponse(
    val availability: DriverAvailability,
    val accountStatus: DriverAccountStatus,
    val updatedAt: Instant,
)
