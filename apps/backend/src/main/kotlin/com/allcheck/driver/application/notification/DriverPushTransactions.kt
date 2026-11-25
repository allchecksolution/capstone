package com.allcheck.driver.application.notification

import com.allcheck.driver.domain.entity.notification.DriverNotificationEvent
import com.allcheck.driver.domain.entity.notification.DriverPushDelivery
import com.allcheck.driver.domain.vo.registration.DriverAccountStatus
import com.allcheck.driver.infrastructure.notification.DriverPushProperties
import com.allcheck.driver.infrastructure.repository.notification.DriverNotificationEventRepository
import com.allcheck.driver.infrastructure.repository.notification.DriverPushDeliveryRepository
import com.allcheck.driver.infrastructure.repository.registration.DriverRepository
import com.allcheck.identity.PushDeviceOperations
import com.allcheck.identity.PushTarget
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock

data class DriverPushClaim(val deliveryId: Long, val claimId: String, val eventId: Long, val accountId: Long, val installationId: String)

@Service
@Transactional(readOnly = true)
class DriverPushTransactions(
    private val events: DriverNotificationEventRepository,
    private val deliveries: DriverPushDeliveryRepository,
    private val drivers: DriverRepository,
    private val devices: PushDeviceOperations,
    private val properties: DriverPushProperties,
    private val clock: Clock = Clock.systemUTC(),
) {
    @Transactional
    fun expandOne(): Boolean {
        val event = events.nextPending() ?: return false
        if (!isCurrent(event)) {
            event.skip()
            return true
        }
        val recipients = event.recipientAccountId?.let { listOf(it) } ?: devices.activeAdminAccountIds()
        val targets = devices.activeDevices(recipients)
        deliveries.saveAll(targets.map { DriverPushDelivery(checkNotNull(event.id), it.accountId, it.installationId, clock.instant()) })
        event.expand()
        return true
    }

    @Transactional
    fun claimOne(): DriverPushClaim? {
        val now = clock.instant()
        val delivery = deliveries.nextPending(now) ?: return null
        val claim = delivery.claim(now, now.plus(properties.lease))
        return DriverPushClaim(checkNotNull(delivery.id), claim, delivery.eventId, delivery.recipientAccountId, delivery.installationId)
    }

    fun target(claim: DriverPushClaim): PushTarget? {
        val delivery = deliveries.findById(claim.deliveryId).orElse(null) ?: return null
        if (delivery.status != "PROCESSING" || delivery.claimId != claim.claimId) return null
        val expiresAt = delivery.leaseExpiresAt ?: return null
        if (!clock.instant().isBefore(expiresAt)) return null
        val event = events.findById(claim.eventId).orElse(null) ?: return null
        if (!isCurrent(event)) return null
        if (event.recipientAccountId != null && event.recipientAccountId != claim.accountId) return null
        if (event.recipientAccountId == null && !devices.isActiveAdministrator(claim.accountId)) return null
        return devices.currentDevice(claim.installationId, claim.accountId)
    }

    fun event(eventId: Long): DriverPushData {
        val event = events.findById(eventId).orElseThrow()
        return DriverPushData(checkNotNull(event.id), event.eventType, event.driverId, event.revision)
    }

    @Transactional
    fun finish(claim: DriverPushClaim, result: DriverPushResult?) {
        val delivery = deliveries.findForUpdate(claim.deliveryId) ?: return
        val outcome = when {
            result == null -> "SKIPPED"
            result.messageId != null -> "PROVIDER_ACCEPTED"
            else -> "FAILED"
        }
        delivery.finish(claim.claimId, outcome, result?.messageId, result?.errorCode, clock.instant())
    }

    private fun isCurrent(event: DriverNotificationEvent): Boolean {
        val driver = drivers.findById(event.driverId).orElse(null) ?: return false
        if (driver.revision != event.revision) return false
        return when (event.eventType) {
            "REGISTRATION_SUBMITTED" -> driver.accountStatus == DriverAccountStatus.PENDING_APPROVAL
            "DRIVER_APPROVED" -> driver.accountStatus == DriverAccountStatus.ACTIVATED
            "DRIVER_REJECTED" -> driver.accountStatus == DriverAccountStatus.REJECTED
            else -> false
        }
    }
}
