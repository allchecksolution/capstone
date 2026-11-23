package com.allcheck.dashboard.presentation

import com.allcheck.dashboard.application.DashboardCriteria
import com.allcheck.dashboard.application.DashboardService
import com.allcheck.dashboard.presentation.api.DashboardApi
import com.allcheck.dashboard.presentation.dto.request.DashboardParameters
import com.allcheck.dashboard.presentation.dto.response.DashboardPage
import com.allcheck.dashboard.presentation.dto.response.DashboardStatistics
import com.allcheck.global.exception.BusinessException
import com.allcheck.global.security.exception.SecurityErrorCode
import com.allcheck.global.security.principal.AuthPrincipal
import com.allcheck.identity.UserType
import com.allcheck.operations.validInput
import org.springframework.http.CacheControl
import org.springframework.http.ResponseEntity
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.RestController
import java.time.LocalDate

@RestController
@PreAuthorize("hasRole('ADMIN')")
internal class DashboardController(private val dashboard: DashboardService) : DashboardApi {

    override fun requests(principal: AuthPrincipal?, filter: DashboardParameters, from: LocalDate?, to: LocalDate?, page: Int, size: Int): ResponseEntity<DashboardPage> {
        requireAdministrator(principal)
        return ok(dashboard.requests(filter.toCriteria(from, to), page, size))
    }

    override fun statistics(principal: AuthPrincipal?, filter: DashboardParameters, from: LocalDate, to: LocalDate): ResponseEntity<DashboardStatistics> {
        requireAdministrator(principal)
        return ok(dashboard.statistics(filter.toCriteria(from, to)))
    }

    private fun DashboardParameters.toCriteria(from: LocalDate?, to: LocalDate?): DashboardCriteria {
        validInput(status == null || requestStatus == null || status == requestStatus)
        return DashboardCriteria(
            from = from,
            to = to,
            requestStatus = requestStatus ?: status,
            workStatus = workStatus,
            workType = workType,
            driverId = driverId,
            region = region,
            needsAdminAction = needsAdminAction,
            query = q,
        )
    }

    private fun requireAdministrator(principal: AuthPrincipal?) {
        if (principal?.userType != UserType.ADMIN) throw BusinessException(SecurityErrorCode.ACCESS_DENIED)
    }

    private fun <T> ok(body: T) = ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body)
}
