package com.allcheck.operations.dispatch

import com.allcheck.operations.DeliveryStatus
import com.allcheck.operations.OfferStatus
import com.allcheck.operations.RoundStatus
import java.time.Duration
import java.time.Instant

interface DispatchOperations {
    fun create(requestId: Long, now: Instant): RoundSnapshot
    fun round(roundId: Long): RoundSnapshot
    fun rounds(requestId: Long): List<RoundSnapshot>
    fun offer(offerId: Long): OfferSnapshot
    fun offers(roundId: Long): List<OfferSnapshot>
    fun driverOffers(driverId: Long, page: Int, size: Int): List<OfferSnapshot>
    fun expand(roundId: Long): Boolean
    fun publish(roundId: Long, candidates: Map<Long, Double>, now: Instant, lifetime: Duration): List<OfferSnapshot>
    fun close(roundId: Long, outcome: RoundStatus, now: Instant)
    fun accept(offerId: Long, now: Instant)
    fun reject(offerId: Long, now: Instant)
    fun reconcile(roundId: Long, now: Instant): RoundStatus?
    fun delivery(offerId: Long, status: DeliveryStatus)
    fun signal(offerId: Long, viewed: Boolean, now: Instant)
    fun expiringRequests(now: Instant, limit: Int): List<Long>
}
data class RoundSnapshot(val roundId: Long, val requestId: Long, val status: RoundStatus, val radiusKm: Int, val createdAt: Instant, val closedAt: Instant?)
data class OfferSnapshot(val offerId: Long, val roundId: Long, val requestId: Long, val driverId: Long, val status: OfferStatus, val deliveryStatus: DeliveryStatus, val distanceMeters: Double, val createdAt: Instant, val expiresAt: Instant, val respondedAt: Instant?, val receivedAt: Instant?, val viewedAt: Instant?)
