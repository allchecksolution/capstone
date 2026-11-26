package com.allcheck.driver.presentation.dispatch

import com.allcheck.driver.application.dispatch.DriverDispatchStateService
import com.allcheck.driver.presentation.api.dispatch.DriverDispatchApi
import com.allcheck.driver.presentation.dto.request.dispatch.AvailabilityUpdateRequest
import com.allcheck.driver.presentation.dto.request.dispatch.LocationReportRequest
import com.allcheck.global.security.principal.AuthPrincipal
import org.springframework.http.CacheControl
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.RestController

@RestController
class DriverDispatchController(private val dispatch: DriverDispatchStateService) : DriverDispatchApi {
    override fun availability(principal: AuthPrincipal) = ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(dispatch.availability(principal.accountId))

    override fun updateAvailability(principal: AuthPrincipal, body: AvailabilityUpdateRequest) = ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(dispatch.updateAvailability(principal.accountId, body.availability))

    override fun reportLocation(principal: AuthPrincipal, body: LocationReportRequest) = ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(dispatch.reportLocation(principal.accountId, body.toReport()))
}
