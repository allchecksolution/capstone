package com.allcheck.driver.presentation.dto.response.dispatch

import com.allcheck.driver.domain.vo.dispatch.LocationIssue
import com.allcheck.driver.domain.vo.dispatch.LocationUsability
import java.time.Instant

data class LocationStateResponse(
    val locationUsability: LocationUsability,
    val lastUpdatedAt: Instant?,
    val issue: LocationIssue?,
)
