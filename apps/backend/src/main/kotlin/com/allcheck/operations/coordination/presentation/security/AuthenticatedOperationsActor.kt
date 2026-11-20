package com.allcheck.operations.coordination.presentation.security

import com.allcheck.driver.DriverOperations
import com.allcheck.global.security.principal.AuthPrincipal
import com.allcheck.identity.UserType
import com.allcheck.operations.ActorRole
import com.allcheck.operations.OperationsActor
import com.allcheck.operations.OperationsIdentity
import org.springframework.security.core.Authentication
import org.springframework.stereotype.Component

@Component
class AuthenticatedOperationsActor(private val drivers: DriverOperations) : OperationsIdentity {
    override fun resolve(authentication: Authentication): OperationsActor? {
        if (!authentication.isAuthenticated) return null
        val principal = authentication.principal as? AuthPrincipal ?: return null
        return when (principal.userType) {
            UserType.ADMIN -> OperationsActor(ActorRole.ADMIN, principal.accountId)
            UserType.DRIVER -> drivers.findActiveDriverByAccountId(principal.accountId)
                ?.let { OperationsActor(ActorRole.DRIVER, it.driverId) }
        }
    }
}
