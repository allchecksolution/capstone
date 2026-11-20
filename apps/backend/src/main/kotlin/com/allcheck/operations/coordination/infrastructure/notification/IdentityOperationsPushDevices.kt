package com.allcheck.operations.coordination.infrastructure.notification

import com.allcheck.driver.DriverOperations
import com.allcheck.identity.PushDeviceOperations
import com.allcheck.operations.ActorRole
import com.allcheck.operations.OperationsActor
import com.allcheck.operations.OperationsPushDevices
import com.allcheck.operations.PushBinding
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

    override fun current(actor: OperationsActor, installationId: String): PushBinding? {
        val accountId = accountId(actor) ?: return null
        return devices.currentDevice(installationId, accountId)?.let { PushBinding(it.token, it.bindingVersion.toString()) }
    }

    override fun invalidateIfCurrent(actor: OperationsActor, installationId: String, binding: PushBinding) {
        val accountId = accountId(actor) ?: return
        val target = devices.currentDevice(installationId, accountId) ?: return
        if (target.bindingVersion.toString() == binding.version && target.token == binding.token) devices.invalidate(target)
    }

    private fun accountId(actor: OperationsActor): Long? = when (actor.role) {
        ActorRole.ADMIN -> actor.id.takeIf(devices::isActiveAdministrator)
        ActorRole.DRIVER -> drivers.findActiveDriverById(actor.id)?.accountId
    }
}
