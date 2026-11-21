package com.allcheck.operations.coordination.presentation.security

import com.allcheck.driver.DriverOperations
import com.allcheck.global.exception.BusinessException
import com.allcheck.global.security.exception.SecurityErrorCode
import com.allcheck.global.security.principal.AuthPrincipal
import com.allcheck.identity.UserType
import com.allcheck.operations.ActorRole
import com.allcheck.operations.OperationsActor
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.stereotype.Component

@Component
class OperationsActorResolver(private val drivers: DriverOperations) {
    fun resolve(principal: AuthPrincipal?): OperationsActor {
        if (principal == null) throw BusinessException(SecurityErrorCode.ACCESS_DENIED)
        val authentication = SecurityContextHolder.getContext().authentication
        if (authentication?.isAuthenticated != true || authentication.authorities.none { it.authority == "ROLE_${principal.userType.name}" }) {
            throw BusinessException(SecurityErrorCode.ACCESS_DENIED)
        }

        return when (principal.userType) {
            UserType.ADMIN -> OperationsActor(ActorRole.ADMIN, principal.accountId)
            UserType.DRIVER -> {
                val driver = drivers.findActiveDriverByAccountId(principal.accountId) ?: throw BusinessException(SecurityErrorCode.ACCESS_DENIED)
                OperationsActor(ActorRole.DRIVER, driver.driverId)
            }
        }
    }
}
