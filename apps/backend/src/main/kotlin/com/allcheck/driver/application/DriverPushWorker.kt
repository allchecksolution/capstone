package com.allcheck.driver.application

import com.allcheck.identity.PushDeviceOperations
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

@Component
class DriverPushWorker(
    private val transactions: DriverPushTransactions,
    private val client: DriverPushSender,
    private val devices: PushDeviceOperations,
) {
    @Scheduled(fixedDelayString = "\${driver.push.poll-interval:PT5S}")
    fun runOnce() {
        if (!client.isConfigured()) return
        transactions.expandOne()
        val claim = transactions.claimOne() ?: return
        val target = transactions.target(claim)
        if (target == null) {
            transactions.finish(claim, null)
            return
        }
        // FCM 호출은 DB claim 트랜잭션이 끝난 뒤 수행한다.
        val result = client.send(target, transactions.event(claim.eventId)) {
            transactions.target(claim)?.tokenHash == target.tokenHash
        }
        if (result?.invalidToken == true) devices.invalidate(target)
        transactions.finish(claim, result)
    }
}
