package com.allcheck.reception.infrastructure.notification

import com.allcheck.identity.PushDeviceOperations
import com.allcheck.reception.ReceptionPushBinding
import com.allcheck.reception.ReceptionPushDevices
import org.springframework.stereotype.Component

@Component
class IdentityReceptionPushDevices(private val devices: PushDeviceOperations) : ReceptionPushDevices {
    override fun activeInstallations(adminId: Long): List<String> = if (devices.isActiveAdministrator(adminId)) devices.activeDevices(listOf(adminId)).map { it.installationId } else emptyList()

    override fun currentBinding(adminId: Long, installationId: String): ReceptionPushBinding? = if (devices.isActiveAdministrator(adminId)) {
        devices.currentDevice(installationId, adminId)?.let { ReceptionPushBinding(it.token, it.bindingVersion) }
    } else {
        null
    }

    override fun invalidateBinding(adminId: Long, installationId: String, rejected: ReceptionPushBinding) {
        val target = devices.currentDevice(installationId, adminId) ?: return
        if (target.bindingVersion == rejected.version && target.token == rejected.token) devices.invalidate(target)
    }
}
