package com.allcheck.driver.presentation.api.dispatch

import com.allcheck.driver.presentation.dto.request.dispatch.AvailabilityUpdateRequest
import com.allcheck.driver.presentation.dto.request.dispatch.LocationReportRequest
import com.allcheck.driver.presentation.dto.response.dispatch.AvailabilityStateResponse
import com.allcheck.driver.presentation.dto.response.dispatch.LocationStateResponse
import com.allcheck.global.security.principal.AuthPrincipal
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.http.ResponseEntity
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping

@Tag(name = "Drivers")
@RequestMapping("/api/v1/drivers/me")
@PreAuthorize("hasRole('DRIVER')")
interface DriverDispatchApi {
    @GetMapping("/availability")
    @Operation(operationId = "getMyAvailability", summary = "배차 대기 상태 조회")
    fun availability(@AuthenticationPrincipal principal: AuthPrincipal): ResponseEntity<AvailabilityStateResponse>

    @PatchMapping("/availability")
    @Operation(operationId = "updateMyAvailability", summary = "배차 대기 상태 변경")
    fun updateAvailability(@AuthenticationPrincipal principal: AuthPrincipal, @RequestBody body: AvailabilityUpdateRequest): ResponseEntity<AvailabilityStateResponse>

    @PutMapping("/location")
    @Operation(operationId = "updateMyLocation", summary = "최신 위치 또는 수집 오류 전송")
    fun reportLocation(@AuthenticationPrincipal principal: AuthPrincipal, @RequestBody body: LocationReportRequest): ResponseEntity<LocationStateResponse>
}
