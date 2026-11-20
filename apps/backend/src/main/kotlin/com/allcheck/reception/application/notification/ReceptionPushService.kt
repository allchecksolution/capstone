package com.allcheck.reception.application.notification

import com.allcheck.global.notification.FcmClient
import com.allcheck.global.notification.FcmException
import com.allcheck.global.notification.FcmFailure
import com.allcheck.identity.PushDeviceOperations
import com.allcheck.identity.PushTarget
import com.allcheck.reception.domain.vo.ReceptionExternalId
import org.springframework.orm.ObjectOptimisticLockingFailureException
import org.springframework.stereotype.Service
import java.time.Duration
import java.util.UUID

/** 네트워크 및 Identity 조회는 발송 체크포인트 트랜잭션 밖에서 실행한다. */
@Service
class ReceptionPushService(private val transactions: ReceptionPushTransactions, private val devices: PushDeviceOperations, private val fcm: FcmClient) {
    fun expand(event: PendingPushEvent) {
        val installations = if (devices.isActiveAdministrator(event.adminId)) devices.activeDevices(listOf(event.adminId)).map { it.installationId } else emptyList()
        transactions.expand(event.eventId, installations)
    }

    fun send(deliveryId: Long, ttl: Duration) {
        val task = try {
            transactions.claim(deliveryId, UUID.randomUUID().toString(), ttl)
        } catch (_: ObjectOptimisticLockingFailureException) {
            return
        } ?: return
        val target = currentTarget(task.adminId, task.installationId)
        if (target == null) {
            transactions.skip(task)
            return
        }
        if (!transactions.prepareSend(task)) return
        val payload = mapOf(
            "eventId" to ReceptionExternalId.EVENT.format(task.eventId),
            "eventType" to task.eventType,
            "receptionId" to ReceptionExternalId.RECEPTION.format(task.receptionId),
            "analysisRequestId" to ReceptionExternalId.ANALYSIS.format(task.analysisId),
        )
        val providerId = try {
            fcm.send(target.token, payload) {
                val current = currentTarget(task.adminId, task.installationId)
                when {
                    current == null -> {
                        transactions.skip(task)
                        false
                    }
                    !current.sameBinding(target) -> {
                        transactions.failed(task, "TOKEN_ROTATED", true, null)
                        false
                    }
                    else -> transactions.prepareSend(task)
                }
            }
        } catch (failure: FcmException) {
            transactions.failed(task, failure.failure.name, failure.failure.retryable, failure.retryAfter)
            if (failure.failure == FcmFailure.UNREGISTERED && devices.currentDevice(task.installationId, task.adminId)?.sameBinding(target) == true) {
                devices.invalidate(target)
            }
            return
        }
        transactions.accepted(task, providerId)
    }

    private fun currentTarget(adminId: Long, installationId: String): PushTarget? {
        if (!devices.isActiveAdministrator(adminId)) return null
        return devices.currentDevice(installationId, adminId)
    }
}
