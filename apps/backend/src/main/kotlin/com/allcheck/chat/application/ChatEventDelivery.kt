package com.allcheck.chat.application

import com.allcheck.chat.domain.vo.ChatSide
import com.allcheck.chat.infrastructure.ChatEventRepository
import com.allcheck.chat.infrastructure.ChatPushDelivery
import com.allcheck.chat.infrastructure.PushDeliveryStatus
import com.allcheck.driver.DriverOperations
import com.allcheck.global.notification.FcmClient
import com.allcheck.global.notification.FcmException
import com.allcheck.global.notification.FcmFailure
import com.allcheck.global.notification.FcmNotification
import com.allcheck.identity.PushDeviceOperations
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.scheduling.annotation.EnableScheduling
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler
import org.springframework.stereotype.Component
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Duration
import java.util.Locale

/** Replace the local WebSocket fan-out and presence check together when deploying multiple instances. */
internal interface ChatRealtimeDelivery {
    fun publish(messageId: Long, driverId: Long, sender: ChatSide): Boolean
    fun hasRecipient(driverId: Long, side: ChatSide): Boolean
}

@Service
@Transactional(readOnly = true)
internal class ChatDeliveryTransactions(private val events: ChatEventRepository) {

    @Transactional
    fun finishEvent(messageId: Long, recipients: List<Pair<Long, String>>) {
        if (!events.completeEvent(messageId) || !events.isUnread(messageId)) return
        recipients.forEach { (accountId, installationId) -> events.enqueuePush(messageId, accountId, installationId) }
    }

    @Transactional
    fun claimPush(leaseSeconds: Long) = events.claimPush(leaseSeconds)

    @Transactional
    fun finishPush(delivery: ChatPushDelivery, status: PushDeliveryStatus, delaySeconds: Long = 0) = events.completePush(delivery, status, delaySeconds)
}

@Component
@ConditionalOnProperty(name = ["chat.delivery.enabled"], havingValue = "true", matchIfMissing = true)
internal class ChatDeliveryWorker(
    private val events: ChatEventRepository,
    private val transactions: ChatDeliveryTransactions,
    private val realtime: ChatRealtimeDelivery,
    private val fcm: FcmClient,
    private val devices: PushDeviceOperations,
    private val drivers: DriverOperations,
    @Value("\${notification.fcm.request-timeout:}") requestTimeout: Duration?,
) {

    private val leaseSeconds = (requestTimeout ?: Duration.ofSeconds(10)).plusSeconds(60).seconds

    @Scheduled(fixedDelayString = "\${chat.delivery.event-poll-interval:250ms}", scheduler = "chatDeliveryScheduler")
    fun dispatchEvents() {
        events.pendingEvents(50).forEach { event ->
            val driver = drivers.findActiveDriverById(event.driverId)
            val recipients = if (driver == null || realtime.publish(event.messageId, event.driverId, event.sender) || event.read) {
                emptyList()
            } else {
                val accounts = when (event.sender) {
                    ChatSide.ADMIN -> listOf(driver.accountId)
                    ChatSide.DRIVER -> devices.activeAdminAccountIds()
                }
                devices.activeDevices(accounts).map { it.accountId to it.installationId }
            }
            transactions.finishEvent(event.messageId, recipients)
        }
    }

    @Scheduled(fixedDelayString = "\${chat.delivery.push-poll-interval:500ms}", scheduler = "chatDeliveryScheduler")
    fun dispatchPush() {
        if (!fcm.isConfigured()) return
        repeat(20) {
            val delivery = transactions.claimPush(leaseSeconds) ?: return
            deliver(delivery)
        }
    }

    private fun deliver(delivery: ChatPushDelivery) {
        if (delivery.attempt > MAX_ATTEMPTS) {
            transactions.finishPush(delivery, PushDeliveryStatus.FAILED)
            return
        }
        val target = devices.currentDevice(delivery.installationId, delivery.accountId)
        if (target == null || !eligible(delivery)) {
            transactions.finishPush(delivery, PushDeliveryStatus.SKIPPED)
            return
        }
        try {
            fcm.send(
                target.token,
                mapOf(
                    "notificationId" to "chat_${delivery.messageId}",
                    "type" to "chat_received",
                    "resourceId" to delivery.driverId.toString(),
                    "messageId" to "msg_${delivery.messageId}",
                    "role" to delivery.recipientSide.name.lowercase(Locale.ROOT),
                ),
                FcmNotification("올체크", "새 메시지가 도착했습니다."),
            ) {
                events.hasLease(delivery) &&
                    eligible(delivery) &&
                    devices.currentDevice(delivery.installationId, delivery.accountId)?.sameBinding(target) == true
            }
            transactions.finishPush(delivery, PushDeliveryStatus.PROVIDER_ACCEPTED)
        } catch (failure: FcmException) {
            if (failure.failure == FcmFailure.UNREGISTERED) devices.invalidate(target)
            when {
                failure.failure == FcmFailure.CANCELLED -> transactions.finishPush(delivery, PushDeliveryStatus.SKIPPED)
                failure.failure.retryable && delivery.attempt < MAX_ATTEMPTS -> {
                    val delay = maxOf(15L * delivery.attempt, failure.retryAfter?.seconds ?: 0L)
                    transactions.finishPush(delivery, PushDeliveryStatus.PENDING, delay)
                }
                else -> transactions.finishPush(delivery, PushDeliveryStatus.FAILED)
            }
        }
    }

    private fun eligible(delivery: ChatPushDelivery): Boolean {
        val driver = drivers.findActiveDriverById(delivery.driverId) ?: return false
        val hasAccess = when (delivery.recipientSide) {
            ChatSide.ADMIN -> devices.isActiveAdministrator(delivery.accountId)
            ChatSide.DRIVER -> driver.accountId == delivery.accountId
        }
        return hasAccess && events.isUnread(delivery.messageId) && !realtime.hasRecipient(delivery.driverId, delivery.recipientSide)
    }

    companion object {
        private const val MAX_ATTEMPTS = 3
    }
}

@Configuration
@EnableScheduling
internal class ChatDeliveryConfiguration {

    @Bean(defaultCandidate = false)
    fun chatDeliveryScheduler(): ThreadPoolTaskScheduler = ThreadPoolTaskScheduler().apply {
        poolSize = 2
        setThreadNamePrefix("chat-delivery-")
    }
}
