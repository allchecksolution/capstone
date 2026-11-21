package com.allcheck.driver.domain.vo.dispatch

import com.allcheck.global.exception.BusinessException
import com.allcheck.global.exception.GlobalErrorCode
import java.time.Instant

sealed interface LocationReport {
    val at: Instant

    data class Position(
        val longitude: Double,
        val latitude: Double,
        val accuracyMeters: Double,
        override val at: Instant,
    ) : LocationReport {
        init {
            if (longitude !in -180.0..180.0 || latitude !in -90.0..90.0 || accuracyMeters !in 0.0..100000.0) {
                throw BusinessException(GlobalErrorCode.INVALID_INPUT_VALUE)
            }
        }

        override fun toString(): String = "Position(at=$at)"
    }

    data class Issue(val issue: LocationIssue, override val at: Instant) : LocationReport
}
