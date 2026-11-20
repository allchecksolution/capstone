package com.allcheck.reception.presentation.security

import com.allcheck.global.security.principal.AuthPrincipal
import com.allcheck.identity.UserType
import com.allcheck.reception.ReceptionAdministratorIdentity
import org.springframework.security.core.Authentication
import org.springframework.stereotype.Component

@Component
class AuthenticatedReceptionAdministrator : ReceptionAdministratorIdentity {
    override fun resolveAdminId(authentication: Authentication): Long? {
        val principal = authentication.principal as? AuthPrincipal ?: return null
        return principal.accountId.takeIf {
            authentication.isAuthenticated &&
                principal.userType == UserType.ADMIN &&
                authentication.authorities.any { it.authority == "ROLE_ADMIN" }
        }
    }
}
