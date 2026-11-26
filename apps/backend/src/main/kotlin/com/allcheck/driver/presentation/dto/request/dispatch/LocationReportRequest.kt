package com.allcheck.driver.presentation.dto.request.dispatch

import com.allcheck.driver.domain.vo.dispatch.LocationIssue
import com.allcheck.driver.domain.vo.dispatch.LocationReport
import com.allcheck.driver.presentation.dto.request.StrictDriverRequest
import com.allcheck.global.exception.BusinessException
import com.allcheck.global.exception.GlobalErrorCode
import java.time.Instant

data class LocationReportRequest(
    val latitude: Double? = null,
    val longitude: Double? = null,
    val accuracyMeters: Double? = null,
    val measuredAt: Instant? = null,
    val issue: LocationIssue? = null,
    val occurredAt: Instant? = null,
) : StrictDriverRequest() {
    fun toReport(): LocationReport {
        val hasPosition = listOf(latitude, longitude, accuracyMeters, measuredAt).any { it != null }
        if (!hasPosition && issue != null && occurredAt != null) return LocationReport.Issue(issue, occurredAt)
        if (issue == null && occurredAt == null && longitude != null && latitude != null && accuracyMeters != null && measuredAt != null) {
            return LocationReport.Position(longitude, latitude, accuracyMeters, measuredAt)
        }
        throw BusinessException(GlobalErrorCode.INVALID_INPUT_VALUE)
    }

    override fun toString(): String = "LocationReportRequest"
}
