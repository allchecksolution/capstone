package com.allcheck.operations.dispatch.domain.entity

import com.allcheck.global.exception.BusinessException
import com.allcheck.operations.DeliveryStatus
import com.allcheck.operations.OfferStatus
import com.allcheck.operations.exception.OperationsErrorCode
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import jakarta.persistence.UniqueConstraint
import jakarta.persistence.Version
import java.time.Instant

@Entity
@Table(name = "dispatch_offers", uniqueConstraints = [UniqueConstraint(columnNames = ["round_id", "driver_id"])])
class DispatchOffer(
    @field:Column(nullable = false, updatable = false) val roundId: Long,
    @field:Column(nullable = false, updatable = false) val requestId: Long,
    @field:Column(nullable = false, updatable = false) val driverId: Long,
    @field:Column(nullable = false, updatable = false) val distanceMeters: Double,
    @field:Column(nullable = false, updatable = false) val createdAt: Instant,
    @field:Column(nullable = false, updatable = false) val expiresAt: Instant,
) {
    @field:Id
    @field:GeneratedValue(strategy = GenerationType.IDENTITY)
    @field:Column(nullable = false, updatable = false)
    var offerId: Long? = null
        protected set

    @field:Enumerated(EnumType.STRING)
    @field:Column(nullable = false, length = 30)
    var status: OfferStatus = OfferStatus.PENDING
        protected set

    @field:Enumerated(EnumType.STRING)
    @field:Column(nullable = false, length = 30)
    var deliveryStatus: DeliveryStatus = DeliveryStatus.PENDING
        protected set

    @field:Column
    var respondedAt: Instant? = null
        protected set

    @field:Column
    var receivedAt: Instant? = null
        protected set

    @field:Column
    var viewedAt: Instant? = null
        protected set

    @field:Version
    @field:Column(nullable = false)
    var persistenceVersion: Long = 0
        protected set

    fun respond(accept: Boolean, now: Instant) {
        if (now >= expiresAt) throw BusinessException(OperationsErrorCode.OFFER_EXPIRED)
        if (status != OfferStatus.PENDING || deliveryStatus == DeliveryStatus.FAILED) throw BusinessException(OperationsErrorCode.OPERATIONS_STATE_CONFLICT)
        status = if (accept) OfferStatus.ACCEPTED else OfferStatus.REJECTED
        respondedAt = now
    }
    fun close() {
        if (status == OfferStatus.PENDING) status = OfferStatus.RESPONSE_CLOSED
    }
    fun expire(now: Instant) {
        if (status == OfferStatus.PENDING && now >= expiresAt && deliveryStatus != DeliveryStatus.FAILED) status = OfferStatus.EXPIRED
    }
    fun delivery(value: DeliveryStatus) {
        if (deliveryStatus != DeliveryStatus.PROVIDER_ACCEPTED) deliveryStatus = value
    }
    fun signal(viewed: Boolean, now: Instant) {
        if (viewed) {
            if (viewedAt == null) viewedAt = now
        } else if (receivedAt == null) {
            receivedAt = now
        }
    }
}
