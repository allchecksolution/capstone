package com.allcheck.operations.coordination.infrastructure.notification

import com.allcheck.driver.DriverOperations
import com.allcheck.identity.PushDeviceOperations
import com.allcheck.identity.PushTarget
import com.allcheck.operations.ActorRole
import com.allcheck.operations.OperationsActor
import com.allcheck.operations.OperationsPushDevices
import org.springframework.stereotype.Component

@Component
class IdentityOperationsPushDevices(
    private val devices: PushDeviceOperations,
    private val drivers: DriverOperations,
) : OperationsPushDevices {
    override fun installations(actor: OperationsActor): List<String> {
        val accountId = accountId(actor) ?: return emptyList()
        return devices.activeDevices(listOf(accountId)).map { it.installationId }
    }

    override fun administrators(): List<Long> = devices.activeAdminAccountIds()

    override fun current(actor: OperationsActor, installationId: String): PushTarget? {
        val accountId = accountId(actor) ?: return null
        return devices.currentDevice(installationId, accountId)
    }

    override fun invalidateIfCurrent(actor: OperationsActor, target: PushTarget) {
        if (current(actor, target.installationId)?.sameBinding(target) == true) devices.invalidate(target)
    }

    private fun accountId(actor: OperationsActor): Long? = when (actor.role) {
        ActorRole.ADMIN -> actor.id.takeIf(devices::isActiveAdministrator)
        ActorRole.DRIVER -> drivers.findActiveDriverById(actor.id)?.accountId
    }
}
