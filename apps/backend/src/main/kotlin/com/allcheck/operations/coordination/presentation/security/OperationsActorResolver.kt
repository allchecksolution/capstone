package com.allcheck.operations.coordination.presentation.security

import com.allcheck.global.exception.BusinessException
import com.allcheck.global.security.exception.SecurityErrorCode
import com.allcheck.operations.OperationsIdentity
import com.allcheck.operations.exception.OperationsErrorCode
import org.springframework.beans.factory.ObjectProvider
import org.springframework.security.core.Authentication
import org.springframework.stereotype.Component

@Component
class OperationsActorResolver(private val identities: ObjectProvider<OperationsIdentity>) {
    fun resolve(authentication: Authentication): com.allcheck.operations.OperationsActor {
        val identity = identities.ifAvailable ?: throw BusinessException(OperationsErrorCode.OPERATIONS_INTEGRATION_UNAVAILABLE)
        val actor = identity.resolve(authentication) ?: throw BusinessException(SecurityErrorCode.ACCESS_DENIED)
        if (authentication.authorities.none { it.authority == "ROLE_${actor.role.name}" }) throw BusinessException(SecurityErrorCode.ACCESS_DENIED)
        return actor
    }
}
