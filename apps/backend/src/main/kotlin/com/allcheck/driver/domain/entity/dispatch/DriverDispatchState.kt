package com.allcheck.driver.domain.entity.dispatch

import com.allcheck.driver.domain.vo.dispatch.DriverAvailability
import com.allcheck.driver.domain.vo.dispatch.LocationIssue
import com.allcheck.driver.domain.vo.dispatch.LocationReport
import com.allcheck.driver.domain.vo.dispatch.LocationUsability
import com.allcheck.global.exception.BusinessException
import com.allcheck.global.exception.GlobalErrorCode
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Duration
import java.time.Instant
import java.time.temporal.ChronoUnit

/** 기사 단위 한 행. 가입·심사 리소스의 버전을 위치 보고마다 변경하지 않는다. */
@Entity
@Table(name = "driver_dispatch_states")
class DriverDispatchState(
    @Id @Column(nullable = false, updatable = false) val driverId: Long,
    availabilityUpdatedAt: Instant,
) {
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    var availability: DriverAvailability = DriverAvailability.UNAVAILABLE
        protected set

    @Column(nullable = false)
    var availabilityUpdatedAt: Instant = availabilityUpdatedAt
        protected set

    var longitude: Double? = null
        protected set

    var latitude: Double? = null
        protected set

    var accuracyMeters: Double? = null
        protected set

    var locationEventAt: Instant? = null
        protected set

    @Enumerated(EnumType.STRING)
    @Column(length = 20)
    var locationIssue: LocationIssue? = null
        protected set

    fun changeAvailability(value: DriverAvailability, now: Instant) {
        if (availability == value) return
        availability = value
        availabilityUpdatedAt = now
    }

    fun reportLocation(report: LocationReport, now: Instant) {
        if (report.at > now) throw BusinessException(GlobalErrorCode.INVALID_INPUT_VALUE)
        val measuredAt = report.at.truncatedTo(ChronoUnit.MICROS)
        if (locationEventAt?.let { measuredAt <= it } == true) return
        val position = report as? LocationReport.Position
        longitude = position?.longitude
        latitude = position?.latitude
        accuracyMeters = position?.accuracyMeters
        locationIssue = (report as? LocationReport.Issue)?.issue
        locationEventAt = measuredAt
    }

    fun usability(now: Instant, validity: Duration): LocationUsability {
        val at = locationEventAt ?: return LocationUsability.MISSING
        if (longitude == null || latitude == null) return LocationUsability.MISSING
        return if (at <= now && at >= now.minus(validity)) LocationUsability.VALID else LocationUsability.STALE
    }
}
