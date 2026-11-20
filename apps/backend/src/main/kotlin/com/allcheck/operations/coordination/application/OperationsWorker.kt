package com.allcheck.operations.coordination.application

import com.allcheck.global.notification.FcmClient
import com.allcheck.global.notification.FcmConnection
import com.allcheck.global.notification.FcmException
import com.allcheck.global.notification.FcmFailure
import com.allcheck.operations.ActorRole
import com.allcheck.operations.DriverLookupException
import com.allcheck.operations.OperationsActor
import com.allcheck.operations.OperationsDrivers
import com.allcheck.operations.OperationsPushDevices
import com.allcheck.operations.domain.entity.TaskKind
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.ObjectProvider
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Configuration
import org.springframework.scheduling.annotation.EnableScheduling
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

@Component
class OperationsWorker(
    private val transactions: OperationsTaskTransactions,
    private val drivers: OperationsDrivers,
    private val devices: ObjectProvider<OperationsPushDevices>,
    private val fcm: FcmClient,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    fun tick() {
        transactions.expiredClaims().forEach { transactions.recover(it) }
        transactions.expiringRequests().forEach { transactions.expireRequest(it) }
        repeat(20) {
            val claim = transactions.claim() ?: return
            try {
                execute(claim)
            } catch (_: Exception) {
                // 이미 커밋한 claim을 지우지 않는다. 만료 복구가 시도 횟수를 보존한다.
                log.error("Operations task execution interrupted: taskId={}, kind={}", claim.id, claim.kind)
            }
        }
    }
    fun execute(claim: TaskClaim) {
        if (!transactions.valid(claim)) {
            if (claim.kind == TaskKind.SEARCH) {
                transactions.searched(claim, null, false)
            } else {
                transactions.delivered(claim, false)
            }
            return
        }
        when (claim.kind) {
            TaskKind.SEARCH -> search(claim)
            TaskKind.EXPAND_OFFER, TaskKind.EXPAND_ADMIN -> expand(claim)
            TaskKind.PUSH_OFFER, TaskKind.PUSH_ADMIN -> push(claim)
        }
    }
    private fun search(claim: TaskClaim) {
        val candidates = try {
            drivers.candidates(transactions.searchContext(claim))
        } catch (failure: DriverLookupException) {
            transactions.searched(claim, null, failure.retryable)
            return
        } catch (_: Exception) {
            transactions.searched(claim, null, false)
            return
        }
        transactions.searched(claim, candidates)
    }
    private fun expand(claim: TaskClaim) {
        val bridge = devices.ifAvailable
        if (bridge == null) {
            transactions.expanded(claim, null)
            return
        }
        val recipients = try {
            val actors = if (claim.kind == TaskKind.EXPAND_OFFER) listOf(transactions.offerActor(claim)) else bridge.administrators().distinct().map { OperationsActor(ActorRole.ADMIN, it) }
            actors.flatMap { actor -> bridge.installations(actor).distinct().map { PushRecipient(actor, it) } }
        } catch (_: Exception) {
            transactions.expanded(claim, null)
            return
        }
        transactions.expanded(claim, recipients)
    }
    private fun push(claim: TaskClaim) {
        val actor = checkNotNull(claim.actor)
        val installation = checkNotNull(claim.installation)
        val bridge = devices.ifAvailable
        if (bridge == null) {
            transactions.delivered(claim, false, true)
            return
        }
        val binding = try {
            bridge.current(actor, installation)
        } catch (_: Exception) {
            transactions.delivered(claim, false, true)
            return
        }
        if (binding == null) {
            transactions.delivered(claim, false)
            return
        }
        try {
            fcm.send(binding.token, transactions.payload(claim)) { transactions.valid(claim) && bridge.current(actor, installation) == binding }
            transactions.delivered(claim, true)
        } catch (failure: FcmException) {
            if (failure.failure == FcmFailure.UNREGISTERED) runCatching { bridge.invalidateIfCurrent(actor, installation, binding) }
            transactions.delivered(claim, false, failure.failure.retryable || failure.failure == FcmFailure.CANCELLED, failure.retryAfter)
        } catch (_: Exception) {
            transactions.delivered(claim, false, true)
        }
    }
}

@Configuration(proxyBeanMethods = false)
@EnableScheduling
@ConditionalOnProperty(name = ["operations.worker.enabled"], havingValue = "true")
class OperationsWorkerSchedule(
    private val worker: OperationsWorker,
    devices: ObjectProvider<OperationsPushDevices>,
    fcm: ObjectProvider<FcmConnection>,
) {
    init {
        require(devices.ifAvailable != null && fcm.ifAvailable != null) {
            "Operations worker requires a push device integration and an enabled FCM connection."
        }
    }

    @Scheduled(fixedDelayString = "\${operations.worker.poll-interval:1s}")
    fun poll() = worker.tick()
}
