package com.allcheck.driver

import java.time.Duration
import java.time.Instant

interface DriverDispatchOperations {
    fun candidates(workType: String, observedAt: Instant): List<DispatchDriver>

    /** Joins the caller's transaction and retains the account and driver locks through its commit. */
    fun lockCandidate(driverId: Long, workType: String): DispatchDriver?
}

class DispatchDriver(
    val driverId: Long,
    val serviceAreas: Set<String>,
    val longitude: Double,
    val latitude: Double,
    val measuredAt: Instant,
    val locationValidity: Duration,
)
