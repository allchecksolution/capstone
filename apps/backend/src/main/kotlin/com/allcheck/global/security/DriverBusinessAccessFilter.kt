package com.allcheck.global.security

import com.allcheck.driver.DriverOperations
import com.allcheck.global.security.handler.CustomAccessDeniedHandler
import com.allcheck.global.security.principal.AuthPrincipal
import com.allcheck.identity.UserType
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.security.access.AccessDeniedException
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher
import org.springframework.web.filter.OncePerRequestFilter

/** HTTP 보안 경계에서는 공개 driver API의 현재 접근 판정만 사용한다. */
class DriverBusinessAccessFilter(
    private val drivers: DriverOperations,
    private val denied: CustomAccessDeniedHandler,
) : OncePerRequestFilter() {
    private val driverRoutes = listOf("availability", "location", "dispatch-offers", "works", "profile", "chat")
        .map { PathPatternRequestMatcher.withDefaults().matcher("/api/v1/drivers/me/$it/**") }
    private val sharedRoutes = listOf("works", "dispatch-offers", "service-requests", "reports")
        .map { PathPatternRequestMatcher.withDefaults().matcher("/api/v1/$it/**") }

    override fun shouldNotFilter(request: HttpServletRequest): Boolean = (driverRoutes + sharedRoutes).none { it.matches(request) }

    override fun doFilterInternal(request: HttpServletRequest, response: HttpServletResponse, filterChain: FilterChain) {
        val principal = SecurityContextHolder.getContext().authentication?.principal as? AuthPrincipal
        val driverOnly = driverRoutes.any { it.matches(request) }
        if (principal != null && ((driverOnly && principal.userType != UserType.DRIVER) || (principal.userType == UserType.DRIVER && !drivers.isActiveDriverAccount(principal.accountId)))) {
            denied.handle(request, response, AccessDeniedException("Driver business access denied."))
            return
        }
        filterChain.doFilter(request, response)
    }
}
