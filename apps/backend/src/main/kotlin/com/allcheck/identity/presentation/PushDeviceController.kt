package com.allcheck.identity.presentation

import com.allcheck.global.security.principal.AuthPrincipal
import com.allcheck.identity.application.PushDeviceService
import com.allcheck.identity.presentation.api.PushDeviceApi
import com.allcheck.identity.presentation.dto.request.PushDeviceRequest
import com.allcheck.identity.presentation.dto.response.PushDeviceResponse
import org.springframework.http.CacheControl
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.RestController

@RestController
class PushDeviceController(private val devices: PushDeviceService) : PushDeviceApi {
    override fun register(principal: AuthPrincipal, installationId: String, request: PushDeviceRequest): ResponseEntity<PushDeviceResponse> {
        val registeredAt = devices.register(principal.accountId, principal.sessionId, installationId, request.platform.name, request.pushToken)
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(PushDeviceResponse(installationId, request.platform.name, registeredAt))
    }

    override fun remove(principal: AuthPrincipal, installationId: String): ResponseEntity<Void> {
        devices.remove(principal.accountId, installationId)
        return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build()
    }
}
