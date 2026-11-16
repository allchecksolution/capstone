package com.allcheck.reception.presentation

import com.allcheck.global.exception.BusinessException
import com.allcheck.global.security.exception.SecurityErrorCode
import com.allcheck.reception.ReceptionAdministratorIdentity
import com.allcheck.reception.exception.ReceptionErrorCode
import org.springframework.beans.factory.ObjectProvider
import org.springframework.security.core.Authentication
import org.springframework.stereotype.Component

@Component
class ReceptionAdminResolver(private val identities: ObjectProvider<ReceptionAdministratorIdentity>) {
    fun resolve(authentication: Authentication): Long {
        val identity = identities.ifAvailable ?: throw BusinessException(ReceptionErrorCode.RECEPTION_IDENTITY_UNAVAILABLE)
        val id = identity.resolveAdminId(authentication) ?: throw BusinessException(SecurityErrorCode.ACCESS_DENIED)
        check(id > 0) { "The identity bridge must return a persisted administrator ID." }
        return id
    }
}
